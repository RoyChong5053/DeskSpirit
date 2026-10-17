package com.roychong.deskspirit.net

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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

        // 换新连接重试一次，但**只在还没吐出任何字的时候**：
        // 已经流出内容再重试会重复半句话。
        var attempt = 0
        while (true) {
            val sb = StringBuilder()
            val think = IntArray(1)
            try {
                streamOnce(url, key, payload, sb, onDelta, think)
                if (sb.isEmpty()) {
                    // 推理模型（如 nemotron / o 系列）会把 token 全花在 reasoning 上，
                    // 正文一个字节都不吐。直接说「没有内容」会把排查带偏，这里分开报。
                    throw IOException(
                        if (think[0] > 0) {
                            "LLM 只输出了思考过程（${think[0]} 字），没有正文。" +
                                "多半是推理模型把 token 用在了 reasoning 上，或输出被上游截断。换一个非推理模型试试"
                        } else {
                            "LLM 没有返回内容（上游可能出错，或模型不支持该参数）"
                        }
                    )
                }
                return sb.toString()
            } catch (e: IOException) {
                if (attempt >= 1 || sb.isNotEmpty() || !Http.isTransportError(e)) throw e
                attempt++
                Log.w(TAG, "LLM 第一次失败（${e.javaClass.simpleName}: ${e.message}），换新连接重试")
            }
        }
    }

    /**
     * 真正的一次流式请求。
     *
     * TavernLab 模式下服务端要先做完 RAG 检索 + 上下文组装才回响应头，
     * embedding/rerank 落在 CPU 时可能要几十秒，所以读超时给到 5 分钟
     * （以前是 30 分钟：那是"连接彻底僵死也要等半小时"，不如早点失败让界面提示）。
     */
    private fun streamOnce(
        url: String,
        key: String,
        payload: JSONObject,
        sb: StringBuilder,
        onDelta: (String) -> Unit,
        think: IntArray,
    ) {
        val conn = Http.open(url, key, "POST", readTimeoutMs = LLM_READ_TIMEOUT_MS)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = Http.readText(conn.errorStream)
                throw IOException("LLM HTTP $code: ${err.take(200)}")
            }

            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val data = line.substring(5).trim()
                    if (data == "[DONE]") break
                    val json = runCatching { JSONObject(data) }.getOrNull() ?: continue

                    // TavernLab 会把上游错误包成 HTTP 200 + data:{"error":...} 透传
                    // （流式响应一旦写出 200 头就没法再改状态码），这里必须显式识别，
                    // 否则只会得到一个含糊的「没有返回内容」。
                    if (json.has("error")) {
                        val e = json.opt("error")
                        val msg = when (e) {
                            is JSONObject -> e.optString("message").ifBlank { e.toString() }
                            else -> e.toString()
                        }
                        throw IOException("LLM 上游报错：${msg.take(300)}")
                    }

                    // 推理模型会先吐 reasoning_content / reasoning，这里只要正文
                    val delta = json.optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")
                    // 只用来判断「是不是全程在思考」，不展示；两种命名都见过：
                    // OpenAI 的 reasoning_content、OpenRouter 的 reasoning。
                    val reasoning = delta?.optString("reasoning_content").orEmpty() +
                        delta?.optString("reasoning").orEmpty()
                    if (reasoning.isNotEmpty()) think[0] += reasoning.length
                    val content = delta?.optString("content").orEmpty()
                    if (content.isNotEmpty()) {
                        sb.append(content)
                        onDelta(content)
                    }
                }
            }
        } finally {
            Http.close(conn)
        }
    }

    private const val TAG = "LlmClient"

    /** 首字/首包的等待上限：TavernLab 的 RAG 可能很慢，5 分钟兜底。 */
    private const val LLM_READ_TIMEOUT_MS = 300_000
}

