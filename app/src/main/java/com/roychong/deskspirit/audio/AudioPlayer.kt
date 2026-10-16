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
 * 不是 MP3，所以这里优先走 AudioTrack；万一返回 mp3 之类就退回 MediaPlayer。
 *
 * 播放过程中把音频能量回馈给渲染层，让球体跟着 AI 的声音跳动。
 */
class AudioPlayer(private val context: Context) {

    @Volatile private var stopped = false

    /** @return 实际使用的解码方式描述，失败抛异常。 */
    fun play(
        contentType: String,
        bytes: ByteArray,
        onLevel: (Spectrum.Bands) -> Unit,
        onDone: () -> Unit
    ) {
        stopped = false
        val type = contentType.lowercase()
        if (type.contains("pcm") || type.contains("l16") || type.contains("wave")) {
            val rate = parseRate(contentType) ?: 24000
            playPcm(bytes, rate, onLevel, onDone)
        } else {
            playFile(bytes, onDone)
        }
    }

    fun stop() {
        stopped = true
    }

    private fun playPcm(
        bytes: ByteArray,
        sampleRate: Int,
        onLevel: (Spectrum.Bands) -> Unit,
        onDone: () -> Unit
    ) {
        val total = bytes.size / 2
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
            .setBufferSizeInBytes(max(4096, total * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val spectrum = Spectrum(1024)
        val chunk = 1024

        try {
            track.play()
            var offset = 0
            while (offset < total && !stopped) {
                val n = if (total - offset < chunk) total - offset else chunk
                val pcm = Spectrum.le16ToShortsAt(bytes, offset, n)
                track.write(pcm, 0, n)
                onLevel(spectrum.analyze(pcm, n))
                offset += n
            }
            // 让缓冲区播完
            var wait = 0
            while (wait < 400 && !stopped) {
                Thread.sleep(10)
                wait++
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
        onDone()
    }

    private fun playFile(bytes: ByteArray, onDone: () -> Unit) {
        val file = File(context.cacheDir, "tts_reply.tmp")
        file.writeBytes(bytes)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            player.setDataSource(file.absolutePath)
            player.prepare()
            player.setOnCompletionListener { onDone() }
            player.start()
            // 阻塞到播完（简化：轮询）
            while (player.isPlaying && !stopped) Thread.sleep(20)
        } catch (e: Exception) {
            Log.e(TAG, "playFile failed", e)
        } finally {
            runCatching { player.release() }
        }
        onDone()
    }

    private fun parseRate(contentType: String): Int? {
        val m = Regex("rate=(\\d+)").find(contentType) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    /** 静音用的 AudioManager，方便把音量调大。 */
    fun audioManager(): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
