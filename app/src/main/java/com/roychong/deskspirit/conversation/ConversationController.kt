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
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.net.AsrClient
import com.roychong.deskspirit.net.LlmClient
import com.roychong.deskspirit.net.TtsClient
import com.roychong.deskspirit.voice.VoiceEntry
import com.roychong.deskspirit.voice.VoiceKind
import com.roychong.deskspirit.voice.VoiceStore
import java.util.concurrent.Executors

/**
 * 一轮对话的编排（轮次互斥）：
 *
 *   常驻听 → VAD 切出一句话 → 关麦 → ASR → LLM(流式) → TTS → 播放 → 开麦
 *
 * 关麦这一步很关键：不然手机喇叭放出来的 AI 声音会被自己听回去。
 *
 * 配置**每轮现读**（`config.llm()/asr()/tts()`），所以在设置里换了 profile 立刻生效。
 * TTS 生成后按设置落进 [VoiceStore]，供重放 / 语音库 / 外部导出。
 *
 * LLM 分两种模式：
 *  - TAVERNLAB：只把这一句话发给 TavernLab，上下文/RAG 记忆全在服务端。
 *  - OPENAI：通用 OpenAI 兼容端点，客户端自己带一小段历史。
 */
class ConversationController(
    context: Context,
    private val config: ApiConfig,
    private val local: AudioSettings,
    private val store: VoiceStore,
    private val onState: (State) -> Unit,
    /** 录音上传进度 0f..1f；1f 表示音频已送达，正在等服务端返回识别结果。 */
    private val onAsrProgress: (Float) -> Unit = {},
    private val onBands: (Spectrum.Bands) -> Unit,
    private val onUserText: (String) -> Unit,
    private val onAiText: (String) -> Unit,
    /** 真正要念出来的文本（已口播清洗），给 KTV 字幕用。 */
    private val onSpeechText: (String) -> Unit,
    /** 播放进度 0f..1f，用来推进字幕高亮。 */
    private val onSpeechProgress: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {

    enum class State { LISTENING, RECOGNIZING, THINKING, SPEAKING, PAUSED }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val player = AudioPlayer(appContext)

    /** 只在 OPENAI 模式下使用。 */
    private val history = ArrayList<LlmClient.Msg>()
    private var historyMode: LlmMode? = null

    private var input: SpeechInput? = null

    @Volatile private var busy = false
    @Volatile private var paused = false

    /** 最近一条 AI 语音（重放用）。 */
    @Volatile private var lastEntry: VoiceEntry? = null

    /** 未落盘时的内存副本，保证"不存档也能重放"。 */
    @Volatile private var lastAudio: Pair<String, ByteArray>? = null

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

    val isBusy: Boolean get() = busy

    /** 有没有可以重放的 AI 语音。 */
    fun canReplay(): Boolean = lastEntry != null || lastAudio != null

    fun startMic() {
        if (input != null) return
        val si = SpeechInput(
            vad = local.vad(),
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
        worker.execute { runTurn(pcm, null) }
    }

    /** 口述 / 编辑后的文本直接进入对话（跳过 ASR）。 */
    fun sendText(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        stopMic()
        worker.execute { runTurn(null, t) }
    }

    /** 重放最近一条 AI 语音（同样关麦防回声）。 */
    fun replayLast(): Boolean {
        val bytes: ByteArray
        val contentType: String
        val fromEntry = lastEntry?.let { store.readBytes(it) }
        if (fromEntry != null) {
            bytes = fromEntry
            contentType = lastEntry!!.contentType
        } else {
            val mem = lastAudio ?: return false
            contentType = mem.first
            bytes = mem.second
        }
        if (busy) return false
        busy = true
        stopMic()
        worker.execute {
            try {
                postState(State.SPEAKING)
                player.play(
                    contentType = contentType,
                    bytes = bytes,
                    onLevel = { onBands(it) },
                    onProgress = { _, _ -> },
                    onDone = {}
                )
            } catch (ex: Exception) {
                Log.e(TAG, "replay failed", ex)
            } finally {
                busy = false
                post { if (!paused) startMic() }
            }
        }
        return true
    }

    private fun runTurn(pcm: ShortArray?, presetText: String?) {
        try {
            val asr = config.asr()
            val llm = config.llm()
            val tts = config.tts()
            val mode = llm.llmMode

            val needAsr = presetText == null
            val missing = when {
                needAsr && !asr.configured -> "ASR"
                !llm.configured -> "LLM"
                !tts.configured -> "TTS"
                else -> null
            }
            if (missing != null) {
                post { onError("请先在设置里填好 $missing") }
                return
            }

            // ---- 用户输入 ----
            val userText: String
            if (presetText != null) {
                userText = presetText
            } else {
                postState(State.RECOGNIZING)
                val wav = Wav.fromPcm16(pcm!!, pcm.size, SpeechInput.SAMPLE_RATE)
                Log.i(TAG, "utterance captured: ${pcm.size} samples, wav ${wav.size} bytes")
                // 调试用：把这一轮听到的东西落盘，方便回放/分析
                runCatching { java.io.File(appContext.cacheDir, "utterance.wav").writeBytes(wav) }
                if (local.saveUserAudio()) {
                    store.save(
                        bytes = wav, contentType = "audio/wav", text = "", kind = VoiceKind.USER,
                        provider = "麦克风",
                        durationMs = pcm.size * 1000 / SpeechInput.SAMPLE_RATE,
                    )
                }
                userText = AsrClient.transcribe(
                    asr.baseUrl, asr.apiKey, asr.model, wav,
                    readTimeoutMs = local.asrTimeoutSec() * 1000,
                ) { sent, total ->
                    if (total > 0) {
                        val p = sent.toFloat() / total
                        post { onAsrProgress(p) }
                    }
                }
                Log.i(TAG, "ASR → $userText")
            }
            post { onUserText(userText) }

            // ---- LLM ----
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
                    prepareHistory(mode)
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
            post { onSpeechText(speakText) }

            val audio = TtsClient.synthesize(
                tts.baseUrl, tts.apiKey, tts.model, tts.voice, speakText,
                readTimeoutMs = local.ttsTimeoutSec() * 1000,
            )
            Log.i(TAG, "TTS → ${audio.contentType} ${audio.bytes.size} bytes")

            // 存进语音库 + 记录用于重放
            if (local.saveVoice()) {
                lastEntry = store.save(
                    bytes = audio.bytes,
                    contentType = audio.contentType,
                    text = speakText,
                    model = tts.model,
                    voice = tts.voice,
                    provider = tts.name,
                    kind = VoiceKind.AI,
                    durationMs = estimateDurationMs(audio.contentType, audio.bytes),
                )
                pruneQuietly()
            } else {
                lastEntry = null
            }
            lastAudio = audio.contentType to audio.bytes

            player.play(
                contentType = audio.contentType,
                bytes = audio.bytes,
                onLevel = { onBands(it) },
                onProgress = { played, total ->
                    if (total > 0) post { onSpeechProgress(played.toFloat() / total) }
                },
                onDone = {}
            )
            post { onSpeechProgress(1f) }
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

    private fun pruneQuietly() {
        runCatching { store.prune(local.voiceMaxCount(), local.voiceMaxDays()) }
    }

    private fun estimateDurationMs(contentType: String, bytes: ByteArray): Int {
        val t = contentType.lowercase()
        if (t.contains("pcm") || t.contains("l16")) {
            val rate = AudioPlayer.parseRate(contentType) ?: 24000
            return bytes.size / 2 * 1000 / rate
        }
        return 0
    }

    /** 通用 OpenAI 模式：切换模式时重置历史（system 钉在 index 0）。 */
    private fun prepareHistory(mode: LlmMode) {
        if (historyMode != mode) {
            history.clear()
            history.add(LlmClient.Msg("system", SYSTEM_PROMPT))
            historyMode = mode
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
