package com.roychong.deskspirit.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * 常驻麦克风监听（旧手机上唯一保留的"重活"）：
 *
 *  - 每帧输出频段能量 → 驱动 3D 圆球
 *  - 用「噪声地板 + 静音时长」做端点检测（VAD），一句话说完就切出音频段
 *
 * 思路沿用 MNN TaoAvatar 的收音逻辑：安静时不乱触发（阈值跟着底噪走），
 * 说完一段静音（约 0.9s）就认为这一轮结束，最长 15s 强制截断。
 */
class SpeechInput(
    private val onFrame: (Spectrum.Bands, Boolean) -> Unit,
    private val onUtterance: (ShortArray) -> Unit,
) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val spectrum = Spectrum(FRAME)

    // ---- VAD 状态 ----
    private var noiseFloor = 0.002f
    private var inSpeech = false
    private var silenceFrames = 0
    private var voicedFrames = 0
    private val buffer = ShortArray(MAX_SAMPLES)
    private var length = 0

    val isRunning: Boolean get() = running

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
            val voice = level > max(MIN_LEVEL, noiseFloor * VOICE_RATIO)

            onFrame(bands, voice || inSpeech)
            updateVad(frame, n, voice)
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

        val tooLong = length >= MAX_SAMPLES
        val ended = silenceFrames * FRAME_MS >= END_SILENCE_MS
        if (ended || tooLong) {
            inSpeech = false

            // 真·有声的时长和占比都要够，否则当成环境噪声丢掉
            val totalFrames = if (length > 0) length / FRAME else 0
            val voicedMs = voicedFrames * FRAME_MS
            val voicedRatio = if (totalFrames > 0) voicedFrames.toFloat() / totalFrames else 0f
            val keep = length >= MIN_SPEECH_SAMPLES && voicedMs >= MIN_VOICED_MS && voicedRatio >= MIN_VOICED_RATIO

            if (keep) {
                // 掐掉尾部多余静音（留 ~200ms）
                val trimFrames = (silenceFrames - 6).coerceAtLeast(0)
                val outLen = (length - trimFrames * FRAME).coerceAtLeast(MIN_SPEECH_SAMPLES)
                val out = ShortArray(outLen)
                System.arraycopy(buffer, 0, out, 0, outLen)
                onUtterance(out)
            }
            length = 0
        }
    }

    private fun append(frame: ShortArray, n: Int) {
        val room = MAX_SAMPLES - length
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

        private const val END_SILENCE_MS = 900
        private const val MIN_SPEECH_MS = 250
        private const val MAX_SPEECH_MS = 15000

        private const val MAX_SAMPLES = SAMPLE_RATE * MAX_SPEECH_MS / 1000
        private const val MIN_SPEECH_SAMPLES = SAMPLE_RATE * MIN_SPEECH_MS / 1000

        /** 一段话至少要有这么多毫秒是“真·有声”，否则当噪声丢弃。 */
        private const val MIN_VOICED_MS = 320
        /** 有声帧占整段的比例下限。 */
        private const val MIN_VOICED_RATIO = 0.30f

        /** 绝对门限（RMS）。环境底噪一般远低于它，正常说话远高于它。 */
        private const val MIN_LEVEL = 0.012f
        /** 相对门限：比噪声地板高这么多倍才算有人说话。 */
        private const val VOICE_RATIO = 3.0f
    }
}
