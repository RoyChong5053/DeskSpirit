package com.roychong.deskspirit.conversation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roychong.deskspirit.audio.AudioPlayer
import com.roychong.deskspirit.audio.SpeechInput
import com.roychong.deskspirit.audio.Spectrum
import com.roychong.deskspirit.audio.Wav
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.net.AsrClient
import com.roychong.deskspirit.net.LlmClient
import com.roychong.deskspirit.net.TtsClient
import java.util.concurrent.Executors

/**
 * 一轮对话的编排（轮次互斥）：
 *
 *   常驻听 → VAD 切出一句话 → 关麦 → ASR → LLM(流式) → TTS → 播放 → 开麦
 *
 * 关麦这一步很关键：不然手机喇叭放出来的 AI 声音会被自己听回去。
 *
 * LLM 分两种模式：
 *  - TAVERNLAB：只把这一句话发给 TavernLab，角色卡 / Context Budget / RAG 记忆 /
 *    蒸馏全在服务端，客户端不保存任何上下文。
 *  - OPENAI：通用 OpenAI 兼容端点，客户端自己带一小段历史（system 钉死不动）。
 */
class ConversationController(
    context: Context,
    private val config: ApiConfig,
    private val onState: (State) -> Unit,
    private val onBands: (Spectrum.Bands) -> Unit,
    private val onUserText: (String) -> Unit,
    private val onAiText: (String) -> Unit,
    private val onError: (String) -> Unit,
) {

    enum class State { LISTENING, RECOGNIZING, THINKING, SPEAKING, PAUSED }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val player = AudioPlayer(appContext)
    private val mode = config.llmMode()

    /** 只在 OPENAI 模式下使用。 */
    private val history = ArrayList<LlmClient.Msg>()

    init {
        // TavernLab 模式下上下文在服务端，客户端不需要 system prompt / 历史
        if (mode == LlmMode.OPENAI) history.add(LlmClient.Msg("system", SYSTEM_PROMPT))
    }

    private var input: SpeechInput? = null

    @Volatile private var busy = false
    @Volatile private var paused = false

    fun start() {
        paused = false
        startMic()
    }

    fun togglePause() {
        if (busy) return
        if (paused) {
            paused = false
            startMic()
        } else {
            paused = true
            stopMic()
            postState(State.PAUSED)
        }
    }

    fun release() {
        stopMic()
        player.stop()
        worker.shutdownNow()
    }

    private fun startMic() {
        if (input != null) return
        val si = SpeechInput(
            onFrame = { bands, _ -> if (!busy) onBands(bands) },
            onUtterance = { pcm -> onUtterance(pcm) }
        )
        if (si.start()) {
            input = si
            postState(State.LISTENING)
        } else {
            post { onError("麦克风启动失败") }
        }
    }

    private fun stopMic() {
        input?.stop()
        input = null
    }

    private fun onUtterance(pcm: ShortArray) {
        if (busy || paused) return
        busy = true
        stopMic()
        worker.execute { runTurn(pcm) }
    }

    private fun runTurn(pcm: ShortArray) {
        try {
            val asr = config.asr()
            val llm = config.llm()
            val tts = config.tts()

            val missing = when {
                !asr.configured -> "ASR"
                !llm.configured -> "LLM"
                !tts.configured -> "TTS"
                else -> null
            }
            if (missing != null) {
                post { onError("请先在设置里填好 $missing") }
                return
            }

            postState(State.RECOGNIZING)
            val wav = Wav.fromPcm16(pcm, pcm.size, SpeechInput.SAMPLE_RATE)
            Log.i(TAG, "utterance captured: ${pcm.size} samples, wav ${wav.size} bytes")
            // 调试用：把这一轮听到的东西落盘，方便回放/分析
            runCatching { java.io.File(appContext.cacheDir, "utterance.wav").writeBytes(wav) }
            val userText = AsrClient.transcribe(asr.baseUrl, asr.apiKey, asr.model, wav)
            Log.i(TAG, "ASR → $userText")
            post { onUserText(userText) }

            postState(State.THINKING)
            val sb = StringBuilder()
            val reply = when (mode) {
                LlmMode.TAVERNLAB -> LlmClient.streamText(
                    llm.baseUrl, llm.apiKey, llm.model, userText
                ) { delta ->
                    sb.append(delta)
                    val partial = sb.toString()
                    post { onAiText(partial) }
                }

                LlmMode.OPENAI -> {
                    history.add(LlmClient.Msg("user", userText))
                    val r = LlmClient.streamChat(
                        llm.baseUrl, llm.apiKey, llm.model, history
                    ) { delta ->
                        sb.append(delta)
                        val partial = sb.toString()
                        post { onAiText(partial) }
                    }
                    history.add(LlmClient.Msg("assistant", r))
                    trimHistory()
                    r
                }
            }
            Log.i(TAG, "LLM → ${reply.length} chars")

            // 角色回复常有 *动作描写* / markdown / emoji，直接念很怪。
            // 只清洗送进 TTS 的那一份，屏幕上的原文保持不动。
            postState(State.SPEAKING)
            val speakText = SpeechText.forSpeech(reply).ifBlank { reply }
            if (speakText != reply) Log.i(TAG, "TTS 清洗 → ${speakText.length} chars（原文 ${reply.length}）")
            Log.i(TAG, "TTS 文本 → ${speakText.take(240)}")
            val audio = TtsClient.synthesize(tts.baseUrl, tts.apiKey, tts.model, tts.voice, speakText)
            Log.i(TAG, "TTS → ${audio.contentType} ${audio.bytes.size} bytes")
            player.play(audio.contentType, audio.bytes, onLevel = { onBands(it) }, onDone = {})
            Log.i(TAG, "playback done")
        } catch (e: Exception) {
            Log.e(TAG, "turn failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            post { onError(msg) }
        } finally {
            busy = false
            post { if (!paused) startMic() }
        }
    }

    /**
     * 通用 OpenAI 模式的历史裁剪：system 钉在 index 0 永不淘汰，
     * 只从最老的 user+assistant 成对删，保证窗口永远以 user 开头。
     */
    private fun trimHistory() {
        while (history.size > 1 + MAX_TURNS * 2) {
            history.removeAt(1)
            if (history.size > 1) history.removeAt(1)
        }
    }

    private fun postState(s: State) = post { onState(s) }

    private fun post(block: () -> Unit) = main.post(block)

    companion object {
        private const val TAG = "Conversation"

        /** 通用 OpenAI 模式最多保留的轮数（TavernLab 模式不用这段历史）。 */
        private const val MAX_TURNS = 8

        /** 仅在通用 OpenAI 模式下使用：桌面伙伴的语气，短、口语化。 */
        private const val SYSTEM_PROMPT =
            "你是 DeskSpirit，一个住在手机里的桌面 AI 伙伴。用简短、口语化的中文回答，" +
                "一般不超过两句话，除非对方明确要你详细说。"
    }
}
