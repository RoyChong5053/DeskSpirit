package com.roychong.deskspirit.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设置页的连通性测试。三个接口都按 OpenAI 兼容形态调用。
 * 这是 v0.1 的"接好就能用"的验证入口。
 */
object ApiTester {

    data class Result(val ok: Boolean, val message: String)

    private fun endpoint(base: String, path: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/v1")) b + path else "$b/v1$path"
    }

    fun testLlm(base: String, key: String, model: String): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        if (model.isBlank()) return Result(false, "Model 为空")
        return try {
            val conn = open(endpoint(base, "/chat/completions"), key, "POST")
            val payload = JSONObject().apply {
                put("model", model)
                put("stream", false)
                put("max_tokens", 32)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("role", "user")
                    put("content", "ping")
                }))
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (code, text) = read(conn)
            if (code in 200..299) {
                val content = JSONObject(text).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                Result(true, "OK · ${content?.trim()?.take(60) ?: "(空回复)"}")
            } else {
                Result(false, "HTTP $code · ${text.take(140)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun testAsr(base: String, key: String, model: String): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        return try {
            // v0.1：ASR 需要音频样本，这里先做鉴权/连通性检查（拉模型列表）。
            val conn = open(endpoint(base, "/models"), key, "GET")
            val (code, text) = read(conn)
            if (code in 200..299) {
                val ids = JSONObject(text).optJSONArray("data")
                    ?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id") }
                    } ?: emptyList()
                val hit = if (model.isNotBlank() && ids.any { it == model }) "（找到 $model）" else ""
                Result(true, "连通 OK $hit")
            } else {
                Result(false, "HTTP $code · ${text.take(140)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun testTts(base: String, key: String, model: String, voice: String): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        if (model.isBlank()) return Result(false, "Model 为空")
        return try {
            val conn = open(endpoint(base, "/audio/speech"), key, "POST")
            val payload = JSONObject().apply {
                put("model", model)
                put("input", "你好，我是 DeskSpirit。")
                if (voice.isNotBlank()) put("voice", voice)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) {
                var bytes = 0L
                conn.inputStream.use { input ->
                    val b = ByteArray(8192)
                    while (true) {
                        val r = input.read(b)
                        if (r < 0) break
                        bytes += r
                    }
                }
                Result(true, "OK · 收到音频 ${bytes / 1024} KB")
            } else {
                val text = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                Result(false, "HTTP $code · ${text.take(140)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun open(url: String, key: String, method: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 45000
        conn.doOutput = method == "POST"
        if (method == "POST") conn.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
        return conn
    }

    private fun read(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
        return code to text
    }
}
