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
     * TavernLab 模式：只发这一句新消息（`text`），
     * 角色卡 / Context Budget / RAG 记忆 / 蒸馏全由服务端负责。
     *
     * 服务端会把这一句写进它的 chat.jsonl，历史从几千轮里自己滑动，
     * 所以客户端**不要**再带 messages。
     */
    fun streamText(
        base: String,
        key: String,
        model: String,
        text: String,
        onDelta: (String) -> Unit
    ): String {
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("text", text)
        return postStream(base, key, payload, onDelta)
    }

    /** 通用 OpenAI 模式：由客户端带完整 messages。 */
    fun streamChat(
        base: String,
        key: String,
        model: String,
        history: List<Msg>,
        onDelta: (String) -> Unit
    ): String {
        val messages = JSONArray()
        for (m in history) {
            messages.put(JSONObject().put("role", m.role).put("content", m.content))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", messages)
        return postStream(base, key, payload, onDelta)
    }

    // ---- 下面是两种模式共用的 HTTP + SSE 解析 ----

    private fun postStream(
        base: String,
        key: String,
        payload: JSONObject,
        onDelta: (String) -> Unit
    ): String {
        val url = Http.endpoint(base, "chat/completions", "/chat/completions")
        if (url.isEmpty()) throw IOException("LLM Base URL 为空")
        val model = payload.optString("model")
        if (model.isBlank()) throw IOException("LLM Model 为空")

        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20000
        // 服务端要先做 RAG 检索，再加上模型本身，首字可能要十几秒
        conn.readTimeout = 240000
        conn.setRequestProperty("Content-Type", "application/json")
        if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
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
                // 推理模型会先吐 reasoning_content，这里只要正文
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
