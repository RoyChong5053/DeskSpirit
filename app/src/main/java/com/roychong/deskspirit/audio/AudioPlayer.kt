package com.roychong.deskspirit.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import java.io.File
import kotlin.math.max

/**
 * 播放 TTS 返回的音频。
 *
 * OpenRouter 的 TTS 返回的是**原始 PCM**（`audio/pcm;rate=24000;channels=1`），
 * 不是 MP3，所以优先走 AudioTrack；万一是 mp3 之类就退回 MediaPlayer。
 *
 * 播放过程中做两件事：
 *  - 把音频能量回馈给渲染层，让球体跟着 AI 的声音跳；
 *  - 上报播放进度，给界面的 KTV 字幕用。
 *
 * **进程内单路播放**：这台手机上曾经出现过两段语音同时出声（两个 Activity 实例
 * 各有一个 player，旧实例的慢 TTS 合成完时新实例正在重播）。所以这里把"当前正在
 * 播放的那一路"放在 companion 里：新播放开始前会先掐掉旧的，任何情况下只有一路声音。
 */
class AudioPlayer(private val context: Context) {

    /** 一次播放的句柄：可由任意线程 cancel（另一个 player 开始播、或用户按停止）。 */
    private class Playback {
        @Volatile var cancelled = false
        @Volatile var track: AudioTrack? = null
        @Volatile var mp: MediaPlayer? = null

        fun cancel() {
            cancelled = true
            val t = track
            track = null
            if (t != null) {
                runCatching { t.pause() }
                runCatching { t.flush() }
                runCatching { t.stop() }
                runCatching { t.release() }
            }
            val m = mp
            mp = null
            if (m != null) {
                runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    /**
     * 开始播放。若已有别的播放在进行，会**先停掉旧的**再开始这一路。
     *
     * @param onStarted 音频真正开始出声时回调（界面据此从「合成中」切到「说话中」）
     */
    fun play(
        contentType: String,
        bytes: ByteArray,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (playedMs: Int, totalMs: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit,
        onStarted: () -> Unit = {},
    ) {
        val pb = Playback()
        val previous = synchronized(LOCK) {
            val old = active
            active = pb
            old
        }
        // 旧的一路必须先停：既避免叠音，也把它占着的 AudioTrack 释放掉
        previous?.cancel()

        val type = contentType.lowercase()
        if (type.contains("pcm") || type.contains("l16") || type.contains("wave")) {
            val rate = parseRate(contentType) ?: 24000
            playPcm(pb, bytes, rate, onLevel, onProgress, onDone, onStarted)
        } else {
            playFile(pb, bytes, onProgress, onDone, onStarted)
        }
    }

    /** 简版：只要播完的通知（语音库重放用）。 */
    fun play(contentType: String, bytes: ByteArray, onDone: () -> Unit) =
        play(contentType, bytes, {}, { _, _ -> }, onDone)

    fun stop() {
        val pb = synchronized(LOCK) {
            val p = active
            active = null
            p
        }
        pb?.cancel()
    }

    /** 供外部把 PCM 包成 WAV 时复用（语音库落盘用）。 */
    fun isPcm(contentType: String): Boolean {
        val t = contentType.lowercase()
        return t.contains("pcm") || t.contains("l16") || t.contains("wave")
    }

    private fun playPcm(
        pb: Playback,
        bytes: ByteArray,
        sampleRate: Int,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit,
        onStarted: () -> Unit,
    ) {
        val total = bytes.size / 2
        if (total <= 0) {
            finish(pb)
            onDone()
            return
        }

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // 关键：缓冲区只留几帧即可。开太大 write() 会瞬间返回，
        // 声音就变成“一次性灌完”，后面必然被截断。
        val bufferSize = if (minBuf > 0) max(minBuf * 4, 8192) else 65536

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        pb.track = track

        val spectrum = Spectrum(1024)
        val chunk = 1024
        val totalMs = (total * 1000L / sampleRate).toInt()

        // 进度回调发声很密（每 1024 帧 ≈ 43ms），节流到 ~50ms 一次
        var lastReport = 0L
        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastReport < 50) return
            lastReport = now
            // 注意用 Long 乘：playbackHeadPosition 是「帧数」，24kHz 下 89 秒后就
            // 超过 Int 除以 1000 的安全范围，用 Int 乘 1000 会溢出成负数，
            // 界面收到的进度就变成负的 → 高亮重置回开头然后永远不动（长文本的假故障）。
            val playedMs = (track.playbackHeadPosition.toLong() * 1000L / sampleRate).toInt()
            onProgress(playedMs.coerceAtMost(totalMs), totalMs)
        }

        try {
            track.play()
            onStarted()
            var offset = 0
            while (offset < total && !pb.cancelled) {
                val n = if (total - offset < chunk) total - offset else chunk
                val pcm = Spectrum.le16ToShortsAt(bytes, offset, n)
                // MODE_STREAM：缓冲区满时 write 会阻塞，天然按实时速度喂数据
                track.write(pcm, 0, n)
                onLevel(spectrum.analyze(pcm, n))
                report()
                offset += n
            }

            // 等缓冲区里的声音真正播完，而不是等固定几秒
            val deadline = System.currentTimeMillis() + totalMs + 3000L
            while (!pb.cancelled && track.playbackHeadPosition < total &&
                System.currentTimeMillis() < deadline
            ) {
                report()
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playPcm failed", e)
        } finally {
            pb.track = null
            runCatching { track.stop() }
            runCatching { track.release() }
            finish(pb)
        }
        onDone()
    }

    private fun playFile(
        pb: Playback,
        bytes: ByteArray,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit,
        onStarted: () -> Unit,
    ) {
        val file = File(context.cacheDir, "tts_reply.tmp")
        val player = MediaPlayer()
        pb.mp = player
        try {
            file.writeBytes(bytes)
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.start()
            onStarted()
            val dur = runCatching { player.duration }.getOrDefault(0)
            while (player.isPlaying && !pb.cancelled) {
                onProgress(player.currentPosition, if (dur > 0) dur else 0)
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
        } finally {
            pb.mp = null
            runCatching { player.release() }
            finish(pb)
        }
        onDone()
    }

    /** 播放结束（正常或被掐断）后把自己从"当前播放"里摘掉。 */
    private fun finish(pb: Playback) {
        synchronized(LOCK) {
            if (active === pb) active = null
        }
    }

    fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioPlayer"

        private val LOCK = Any()

        /** 进程内当前正在播放的那一路（跨 Activity 实例共享）。 */
        private var active: Playback? = null

        /** 从 `audio/pcm;rate=24000` 这类 contentType 里取采样率。 */
        fun parseRate(contentType: String): Int? {
            val m = Regex("rate=(\\d+)").find(contentType) ?: return null
            return m.groupValues[1].toIntOrNull()
        }
    }
}
