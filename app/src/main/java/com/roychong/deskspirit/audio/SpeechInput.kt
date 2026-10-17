package com.roychong.deskspirit.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.roychong.deskspirit.config.VadParams
import kotlin.math.max

/**
 * 常驻麦克风监听（旧手机上唯一保留的"重活"）：
 *
 *  - 每帧输出频段能量 → 驱动 3D 圆球
 *  - Live 模式：用「噪声地板 + 静音时长」做端点检测（VAD），一句话说完就切出音频段
 *  - 口述模式（[autoEndpoint] = false）：只累积，不自动切句，由调用方 [stopAndCollect]
 *
 * 参数全部来自 [VadParams]，可在设置页调整。
 */
class SpeechInput(
    private val vad: VadParams = VadParams(),
    /** true = Live（自动切句回调 [onUtterance]）；false = 口述（只累积，等 [stopAndCollect]）。 */
    private val autoEndpoint: Boolean = true,
    private val onFrame: (Spectrum.Bands, Boolean) -> Unit,
    private val onUtterance: (ShortArray) -> Unit,
) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val spectrum = Spectrum(FRAME)

    private val maxSamples = SAMPLE_RATE * vad.maxSpeechMs / 1000
    private val minSpeechSamples = SAMPLE_RATE * vad.minSpeechMs / 1000

    // ---- VAD 状态 ----
    private var noiseFloor = 0.002f
    private var inSpeech = false
    private var silenceFrames = 0
    private var voicedFrames = 0
    private val buffer = ShortArray(maxSamples)
    private var length = 0

    val isRunning: Boolean get() = running

    /** 已采集的毫秒数。 */
    val collectedMs: Int get() = length * 1000 / SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuf")
            return false
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FRAME * 8)
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord init failed", e)
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Log.e(TAG, "AudioRecord not initialized")
            return false
        }

        inSpeech = false
        silenceFrames = 0
        voicedFrames = 0
        length = 0

        record = rec
        running = true
        rec.startRecording()
        thread = Thread { loop(rec) }.also { it.start() }
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.let { runCatching { it.join(500) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
    }

    /** 口述模式用：停止并取回整段录音（undefined → null）。 */
    fun stopAndCollect(): ShortArray? {
        if (!running) return null
        stop()
        if (length <= 0) return null
        val out = ShortArray(length)
        System.arraycopy(buffer, 0, out, 0, length)
        length = 0
        return out
    }

    private fun loop(rec: AudioRecord) {
        val frame = ShortArray(FRAME)
        while (running) {
            val n = rec.read(frame, 0, FRAME)
            if (n <= 0) continue

            val bands = spectrum.analyze(frame, n)
            val level = spectrum.rms(frame, n)

            // 噪声地板：安静时缓慢上升，有声时缓慢下降
            noiseFloor = if (level < noiseFloor) {
                noiseFloor * 0.97f + level * 0.03f
            } else {
                noiseFloor + (level - noiseFloor) * 0.0008f
            }
            val voice = level > max(vad.minLevel, noiseFloor * vad.voiceRatio)

            onFrame(bands, voice || inSpeech)

            if (autoEndpoint) {
                updateVad(frame, n, voice)
            } else {
                // 口述：一律累积，不做端点判断
                append(frame, n)
                if (voice) voicedFrames++
            }
        }
    }

    private fun updateVad(frame: ShortArray, n: Int, voice: Boolean) {
        if (!inSpeech) {
            if (voice) {
                inSpeech = true
                silenceFrames = 0
                voicedFrames = 0
                length = 0
                append(frame, n)
                voicedFrames = 1
            }
            return
        }

        append(frame, n)
        if (voice) {
            silenceFrames = 0
            voicedFrames++
        } else {
            silenceFrames++
        }

        val tooLong = length >= maxSamples
        val ended = silenceFrames * FRAME_MS >= vad.endSilenceMs
        if (ended || tooLong) {
            inSpeech = false

            // 真·有声的时长和占比都要够，否则当成环境噪声丢掉
            val totalFrames = if (length > 0) length / FRAME else 0
            val voicedMs = voicedFrames * FRAME_MS
            val voicedRatio = if (totalFrames > 0) voicedFrames.toFloat() / totalFrames else 0f
            val keep = length >= minSpeechSamples && voicedMs >= vad.minVoicedMs &&
                voicedRatio >= vad.minVoicedRatio

            if (keep) {
                // 掐掉尾部多余静音（留 ~200ms）
                val trimFrames = (silenceFrames - 6).coerceAtLeast(0)
                val outLen = (length - trimFrames * FRAME).coerceAtLeast(minSpeechSamples)
                val out = ShortArray(outLen)
                System.arraycopy(buffer, 0, out, 0, outLen)
                onUtterance(out)
            }
            length = 0
        }
    }

    private fun append(frame: ShortArray, n: Int) {
        val room = maxSamples - length
        val copy = if (n < room) n else room
        if (copy > 0) {
            System.arraycopy(frame, 0, buffer, length, copy)
            length += copy
        }
    }

    companion object {
        private const val TAG = "SpeechInput"

        const val SAMPLE_RATE = 16000

        /** 每帧 32ms，既能驱动 30fps 的渲染，也够 FFT 用。 */
        private const val FRAME = 512
        private const val FRAME_MS = 32
    }
}
