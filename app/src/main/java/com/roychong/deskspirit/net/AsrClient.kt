package com.roychong.deskspirit.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** ASR：OpenAI 兼容的 `/audio/transcriptions`（multipart 上传 wav）。 */
object AsrClient {

    fun transcribe(base: String, key: String, model: String, wav: ByteArray): String {
        if (model.isBlank()) throw IOException("ASR Model 为空")
        val (code, body) = postWav(base, key, model, wav)
        if (code !in 200..299) throw IOException("ASR HTTP $code: ${body.take(200)}")
        val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
        if (text.isBlank()) throw IOException("ASR 没听清: ${body.take(160)}")
        return text.trim()
    }

    /** 设置页的连通性探测：只关心 HTTP 码，静音返回空文本也算通过。 */
    fun probe(base: String, key: String, model: String, wav: ByteArray): Pair<Int, String> {
        if (model.isBlank()) return -1 to "ASR Model 为空"
        return postWav(base, key, model, wav)
    }

    private fun postWav(base: String, key: String, model: String, wav: ByteArray): Pair<Int, String> {
        val url = Http.endpoint(base, "transcriptions", "/audio/transcriptions")
        if (url.isEmpty()) return -1 to "ASR Base URL 为空"

        val boundary = "----DeskSpirit${System.nanoTime()}"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        conn.readTimeout = 300000
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")

        conn.outputStream.use { out ->
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray())
                out.write("\r\n".toByteArray())
            }
            field("model", model)
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n".toByteArray()
            )
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            out.write(wav)
            out.write("\r\n".toByteArray())
            out.write("--$boundary--\r\n".toByteArray())
        }

        val code = conn.responseCode
        val body = Http.readText(if (code in 200..299) conn.inputStream else conn.errorStream)
        return code to body
    }
}
