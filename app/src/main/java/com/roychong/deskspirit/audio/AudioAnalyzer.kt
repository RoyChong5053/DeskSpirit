package com.roychong.deskspirit.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一帧音频特征，供渲染层使用。
 *
 * @param level  整体响度 0..1
 * @param bass / mid / treble  三个频段能量 0..1
 * @param voice  粗糙的"有人在说话"判断（v0.1 占位，后续替换成真正的 VAD）
 */
data class AudioFrame(
    val level: Float,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val voice: Boolean
)

/**
 * 麦克风采集 + 响度/频谱分析。
 *
 * 这是全 API 化之后仍然保留在本地的一小块：它给渲染提供音频特征，
 * 同时也是后续 VAD（判端点）的底座。不依赖任何大模型。
 */
class AudioAnalyzer(private val onFrame: (AudioFrame) -> Unit) {

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    private val window = FloatArray(FFT_SIZE) {
        0.5f * (1f - cos(2.0 * PI * it / (FFT_SIZE - 1)).toFloat())
    }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)
    private val peak = FloatArray(4) { 1e-3f }
    private var noiseFloor = 0.002f

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
                max(minBuf, FFT_SIZE * 4)
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
        val buf = ShortArray(FFT_SIZE)
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            onFrame(analyze(buf, n))
        }
    }

    private fun analyze(buf: ShortArray, n: Int): AudioFrame {
        var rms = 0.0
        for (i in 0 until n) {
            val s = buf[i] / 32768f
            rms += s.toDouble() * s
        }
        rms = sqrt(rms / n)
        val rmsF = rms.toFloat()
        val level = norm(0, rmsF * 3.0f)

        for (i in 0 until FFT_SIZE) {
            val s = if (i < n) buf[i] / 32768f else 0f
            re[i] = s * window[i]
            im[i] = 0f
        }
        fft(re, im)

        val bass = norm(1, bandEnergy(2, 8))
        val mid = norm(2, bandEnergy(9, 32))
        val treble = norm(3, bandEnergy(33, 128) * 1.6f)

        // 简易噪声地板跟踪：安静时地板缓慢上升，有声时缓慢下降，
        // 避免把环境底噪一直当成“有人在说话”。真正的 VAD 后续替换这里。
        noiseFloor = if (rmsF < noiseFloor) {
            noiseFloor * 0.97f + rmsF * 0.03f
        } else {
            noiseFloor + (rmsF - noiseFloor) * 0.0008f
        }
        val voice = rmsF > 0.006f && rmsF > noiseFloor * 2.6f

        return AudioFrame(level, bass, mid, treble, voice)
    }

    private fun bandEnergy(lo: Int, hi: Int): Float {
        var sum = 0f
        for (k in lo..hi) {
            sum += sqrt(re[k] * re[k] + im[k] * im[k]) / (FFT_SIZE / 4f)
        }
        return sum / (hi - lo + 1)
    }

    /** 自适应峰值归一化：让各频段稳定落在 0..1，不随环境音量漂移。 */
    private fun norm(index: Int, v: Float): Float {
        peak[index] = max(peak[index] * 0.90f, v)
        val p = if (peak[index] < 1e-3f) 1e-3f else peak[index]
        return (v / p).coerceIn(0f, 1f)
    }

    /** 迭代式 radix-2 FFT（原地）。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curR = 1f
                var curI = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = a + half
                    val vR = re[b] * curR - im[b] * curI
                    val vI = re[b] * curI + im[b] * curR
                    re[b] = re[a] - vR
                    im[b] = im[a] - vI
                    re[a] += vR
                    im[a] += vI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        private const val TAG = "AudioAnalyzer"
        const val SAMPLE_RATE = 16000
        private const val FFT_SIZE = 1024
    }
}
