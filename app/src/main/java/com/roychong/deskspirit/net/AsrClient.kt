package com.roychong.deskspirit.net

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** ASR：OpenAI 兼容的 `/audio/transcriptions`（multipart 上传 wav）。 */
object AsrClient {

    /**
     * @param readTimeoutMs 等服务端返回的最长时间。本地 CPU 跑大模型可能要好几分钟，
     *                      所以由调用方按设置传入（默认 90s）。
     * @param onProgress 上传进度（已写入的字节 / 总字节）。每 5% 触发一次，最后一定有一次 total/total。
     *                   注意这是「写进网络缓冲」的进度，局域网里会瞬间到 100% —— 真正的等待在服务端推理。
     */
    fun transcribe(
        base: String,
        key: String,
        model: String,
        wav: ByteArray,
        readTimeoutMs: Int = Http.READ_TIMEOUT_MS,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> },
    ): String {
        if (model.isBlank()) throw IOException("ASR Model 为空")
        val (code, body) = postWav(base, key, model, wav, readTimeoutMs, onProgress)
        if (code !in 200..299) throw IOException("ASR HTTP $code: ${body.take(200)}")
        val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
        if (text.isBlank()) throw IOException("ASR 没听清: ${body.take(160)}")
        return text.trim()
    }

    /** 设置页的连通性探测：只关心 HTTP 码，静音返回空文本也算通过。 */
    fun probe(
        base: String,
        key: String,
        model: String,
        wav: ByteArray,
        readTimeoutMs: Int = Http.READ_TIMEOUT_MS,
    ): Pair<Int, String> {
        if (model.isBlank()) return -1 to "ASR Model 为空"
        return postWav(base, key, model, wav, readTimeoutMs) { _, _ -> }
    }

    /** 整段请求带一次重试（首包阶段失败会换新连接再来一遍）。 */
    private fun postWav(
        base: String,
        key: String,
        model: String,
        wav: ByteArray,
        readTimeoutMs: Int,
        onProgress: (Int, Int) -> Unit,
    ): Pair<Int, String> =
        Http.retryOnce { postWavOnce(base, key, model, wav, readTimeoutMs, onProgress) }

    private fun postWavOnce(
        base: String,
        key: String,
        model: String,
        wav: ByteArray,
        readTimeoutMs: Int,
        onProgress: (Int, Int) -> Unit,
    ): Pair<Int, String> {
        val url = Http.endpoint(base, "transcriptions", "/audio/transcriptions")
        if (url.isEmpty()) return -1 to "ASR Base URL 为空"

        val boundary = "----DeskSpirit${System.nanoTime()}"
        val conn = Http.open(url, key, "POST", readTimeoutMs = readTimeoutMs)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        try {
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

                // 分包写，才能报进度；每 5% 回调一次，避免 UI 线程被打爆
                val total = wav.size
                var sent = 0
                var lastPct = -5
                while (sent < total) {
                    val n = minOf(16 * 1024, total - sent)
                    out.write(wav, sent, n)
                    sent += n
                    val pct = sent * 100 / total
                    if (pct - lastPct >= 5 || sent == total) {
                        lastPct = pct
                        onProgress(sent, total)
                    }
                }
                if (total == 0) onProgress(0, 0)

                out.write("\r\n".toByteArray())
                out.write("--$boundary--\r\n".toByteArray())
            }

            val code = try {
                conn.responseCode
            } catch (e: java.net.SocketTimeoutException) {
                // 音频已经送达，只是服务端还在算。这时重试没有意义（会重新上传 + 重新推理），
                // 直接给一条能指导操作的错误。
                throw IOException(
                    "等待识别结果超时（${readTimeoutMs / 1000} 秒）：本地模型可能太慢，" +
                        "可在「设置 → ASR → 识别超时」把它调大",
                    e,
                )
            }
            val body = Http.readText(if (code in 200..299) conn.inputStream else conn.errorStream)
            return code to body
        } finally {
            Http.close(conn)
        }
    }
}
