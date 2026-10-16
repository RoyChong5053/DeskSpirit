package com.roychong.deskspirit.net

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** LLM：OpenAI 兼容的 `/chat/completions`，流式返回增量文本。 */
object LlmClient {

    data class Msg(val role: String, val content: String)

    /**
     * 流式对话。[onDelta] 在每收到一小段文本时回调（在调用线程上）。
     * @return 完整回复
     */
    fun streamChat(
        base: String,
        key: String,
        model: String,
        history: List<Msg>,
        onDelta: (String) -> Unit
    ): String {
        val url = Http.endpoint(base, "chat/completions", "/chat/completions")
        if (url.isEmpty()) throw IOException("LLM Base URL 为空")
        if (model.isBlank()) throw IOException("LLM Model 为空")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        // auto-agent 这类“路由 + 推理”模型首字可能要 10s 以上，给足时间
        conn.readTimeout = 180000
        conn.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")

        val messages = JSONArray()
        for (m in history) {
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
        conn.outputStream.use { it.write(payload.toString().toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val err = Http.readText(conn.errorStream)
            throw IOException("LLM HTTP $code: ${err.take(200)}")
        }

        val sb = StringBuilder()
        conn.inputStream.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty() || !line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data == "[DONE]") break
                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val delta = json.optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("delta")
                val content = delta?.optString("content").orEmpty()
                if (content.isNotEmpty()) {
                    sb.append(content)
                    onDelta(content)
                }
            }
        }
        if (sb.isEmpty()) throw IOException("LLM 没有返回内容")
        return sb.toString()
    }
}
