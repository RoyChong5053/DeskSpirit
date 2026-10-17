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
 */
class AudioPlayer(private val context: Context) {

    @Volatile private var stopped = false

    fun play(
        contentType: String,
        bytes: ByteArray,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (playedMs: Int, totalMs: Int) -> Unit = { _, _ -> },
        onDone: () -> Unit
    ) {
        stopped = false
        val type = contentType.lowercase()
        if (type.contains("pcm") || type.contains("l16") || type.contains("wave")) {
            val rate = parseRate(contentType) ?: 24000
            playPcm(bytes, rate, onLevel, onProgress, onDone)
        } else {
            playFile(bytes, onProgress, onDone)
        }
    }

    /** 简版：只要播完的通知（语音库重放用）。 */
    fun play(contentType: String, bytes: ByteArray, onDone: () -> Unit) =
        play(contentType, bytes, {}, { _, _ -> }, onDone)

    fun stop() {
        stopped = true
    }

    /** 供外部把 PCM 包成 WAV 时复用（语音库落盘用）。 */
    fun isPcm(contentType: String): Boolean {
        val t = contentType.lowercase()
        return t.contains("pcm") || t.contains("l16") || t.contains("wave")
    }

    private fun playPcm(
        bytes: ByteArray,
        sampleRate: Int,
        onLevel: (Spectrum.Bands) -> Unit,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        val total = bytes.size / 2
        if (total <= 0) {
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

        val spectrum = Spectrum(1024)
        val chunk = 1024
        val totalMs = (total * 1000L / sampleRate).toInt()

        // 进度回调发声很密（每 1024 帧 ≈ 43ms），节流到 ~50ms 一次
        var lastReport = 0L
        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastReport < 50) return
            lastReport = now
            onProgress(track.playbackHeadPosition * 1000 / sampleRate, totalMs)
        }

        try {
            track.play()
            var offset = 0
            while (offset < total && !stopped) {
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
            while (!stopped && track.playbackHeadPosition < total &&
                System.currentTimeMillis() < deadline
            ) {
                report()
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playPcm failed", e)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        onDone()
    }

    private fun playFile(
        bytes: ByteArray,
        onProgress: (Int, Int) -> Unit,
        onDone: () -> Unit
    ) {
        val file = File(context.cacheDir, "tts_reply.tmp")
        val player = MediaPlayer()
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
            val dur = runCatching { player.duration }.getOrDefault(0)
            while (player.isPlaying && !stopped) {
                onProgress(player.currentPosition, if (dur > 0) dur else 0)
                Thread.sleep(50)
            }
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
        } finally {
            runCatching { player.release() }
        }
        onDone()
    }

    fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioPlayer"

        /** 从 `audio/pcm;rate=24000` 这类 contentType 里取采样率。 */
        fun parseRate(contentType: String): Int? {
            val m = Regex("rate=(\\d+)").find(contentType) ?: return null
            return m.groupValues[1].toIntOrNull()
        }
    }
}
