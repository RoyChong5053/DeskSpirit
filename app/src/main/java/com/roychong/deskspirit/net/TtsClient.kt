package com.roychong.deskspirit.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** TTS：OpenAI 兼容的 `/audio/speech`，返回音频字节。 */
object TtsClient {

    /** @param contentType 形如 `audio/pcm;rate=24000;channels=1` */
    data class Audio(val contentType: String, val bytes: ByteArray)

    fun synthesize(
        base: String,
        key: String,
        model: String,
        voice: String,
        text: String
    ): Audio {
        val url = Http.endpoint(base, "audio/speech", "/audio/speech")
        if (url.isEmpty()) throw IOException("TTS Base URL 为空")
        if (model.isBlank()) throw IOException("TTS Model 为空")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        // 长回复的 TTS 合成要几十秒，给足余量
        conn.readTimeout = 300000
        conn.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")

        val payload = JSONObject()
            .put("model", model)
            .put("input", text)
        if (voice.isNotBlank()) payload.put("voice", voice)
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }

        val code = conn.responseCode
        val contentType = (conn.contentType ?: "").lowercase()
        if (code !in 200..299) {
            val err = Http.readText(conn.errorStream)
            throw IOException("TTS HTTP $code: ${err.take(200)}")
        }

        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.isEmpty()) throw IOException("TTS 返回空音频")
        // 有些网关出错时也用 200 + JSON
        if (contentType.contains("json") || contentType.contains("text")) {
            throw IOException("TTS 返回非音频: ${String(bytes).take(200)}")
        }
        return Audio(contentType, bytes)
    }
}
