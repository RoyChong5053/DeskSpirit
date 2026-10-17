package com.roychong.deskspirit.conversation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.roychong.deskspirit.audio.SpeechInput
import com.roychong.deskspirit.audio.Spectrum
import com.roychong.deskspirit.audio.Wav
import com.roychong.deskspirit.config.ApiConfig
import com.roychong.deskspirit.config.AudioSettings
import com.roychong.deskspirit.net.AsrClient
import com.roychong.deskspirit.voice.VoiceEntry
import com.roychong.deskspirit.voice.VoiceKind
import com.roychong.deskspirit.voice.VoiceStore
import java.util.concurrent.Executors

/**
 * 口述（2-pass）：手动开始 → 长时间聆听（VAD 不切句）→ 手动停止 → 整段 ASR。
 * 识别结果交给界面做「编辑 / 整理 / 发送 / 朗读 / 留档」，这里只负责收音与识别。
 */
class DictationController(
    private val config: ApiConfig,
    private val local: AudioSettings,
    private val store: VoiceStore,
    private val onState: (State) -> Unit,
    private val onBands: (Spectrum.Bands) -> Unit,
    private val onText: (String, VoiceEntry?) -> Unit,
    private val onError: (String) -> Unit,
) {

    enum class State { IDLE, RECORDING, RECOGNIZING }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var input: SpeechInput? = null
    private var startedAt = 0L

    private val autoStop = Runnable { stop() }

    val isRecording: Boolean get() = input != null

    fun start(): Boolean {
        if (input != null) return false
        val maxMs = local.dictation().maxMs
        // 口述：把停顿时长拉到极大 → VAD 实务上不切句；缓冲按口述上限算
        val vad = local.vad().copy(endSilenceMs = Int.MAX_VALUE, maxSpeechMs = maxMs)
        val si = SpeechInput(
            vad = vad,
            autoEndpoint = false,
            onFrame = { bands, _ -> onBands(bands) },
            onUtterance = {},
        )
        if (!si.start()) {
            onError("麦克风启动失败")
            return false
        }
        input = si
        startedAt = System.currentTimeMillis()
        onState(State.RECORDING)
        main.postDelayed(autoStop, maxMs.toLong())
        return true
    }

    fun elapsedMs(): Int =
        if (input == null) 0 else (System.currentTimeMillis() - startedAt).toInt()

    fun stop() {
        val si = input ?: return
        input = null
        main.removeCallbacks(autoStop)
        onState(State.RECOGNIZING)

        val pcm = si.stopAndCollect()
        if (pcm == null || pcm.isEmpty()) {
            onError("没有录到声音")
            onState(State.IDLE)
            return
        }
        worker.execute {
            try {
                val asr = config.asr()
                if (!asr.configured) {
                    post { onError("请先在设置里填好 ASR"); onState(State.IDLE) }
                    return@execute
                }
                val wav = Wav.fromPcm16(pcm, pcm.size, SpeechInput.SAMPLE_RATE)
                val text = AsrClient.transcribe(asr.baseUrl, asr.apiKey, asr.model, wav)
                Log.i(TAG, "dictation ASR → ${text.length} chars")
                val entry = store.save(
                    bytes = wav,
                    contentType = "audio/wav",
                    text = text,
                    model = asr.model,
                    provider = asr.name,
                    kind = VoiceKind.DICTATE,
                    durationMs = pcm.size * 1000 / SpeechInput.SAMPLE_RATE,
                )
                post { onText(text, entry); onState(State.IDLE) }
            } catch (e: Exception) {
                Log.e(TAG, "dictation failed", e)
                post { onError(e.message ?: "识别失败"); onState(State.IDLE) }
            }
        }
    }

    fun release() {
        main.removeCallbacks(autoStop)
        input?.stop()
        input = null
        worker.shutdownNow()
    }

    private fun post(block: () -> Unit) = main.post(block)

    companion object {
        private const val TAG = "Dictation"
    }
}
