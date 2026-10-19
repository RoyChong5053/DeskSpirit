package com.roychong.deskspirit.net

import com.roychong.deskspirit.audio.Wav
import com.roychong.deskspirit.config.Endpoint
import com.roychong.deskspirit.config.LlmMode
import com.roychong.deskspirit.config.Slot
import org.json.JSONArray
import org.json.JSONObject

/**
 * 设置页的连通性测试。按真实形态调用，所以"测试通过 = 主界面那一轮也能跑通"。
 */
object ApiTester {

    /**
     * TTS 试听的固定短句。挑一句自然的问句：既验证连通，又能直接听音色/韵律，
     * 而且短 —— 本地 TTS 也能几秒内出结果。
     */
    private const val TTS_PROBE_TEXT = "你好，有什么能帮到你吗？"

    /**
     * @param audio 只有 TTS 测试带回合成结果 —— 设置页拿到它直接试听，才能听出音色。
     */
    data class Result(val ok: Boolean, val message: String, val audio: TtsClient.Audio? = null)

    data class Models(val ok: Boolean, val ids: List<String>, val message: String)

    fun test(slot: Slot, e: Endpoint, asrTimeoutSec: Int = 90, ttsTimeoutSec: Int = 90): Result =
        when (slot) {
            Slot.LLM_DIALOG, Slot.LLM_TOOL -> testLlm(e)
            Slot.ASR -> testAsr(e, asrTimeoutSec)
            Slot.TTS -> testTts(e, ttsTimeoutSec)
        }

    fun testLlm(e: Endpoint): Result {
        if (e.baseUrl.isBlank()) return Result(false, "Base URL 为空")
        if (e.model.isBlank()) return Result(false, "Model 为空")
        return try {
            val t0 = System.currentTimeMillis()
            var ttft = -1L
            val onDelta: (String) -> Unit = {
                if (ttft < 0) ttft = System.currentTimeMillis() - t0
            }
            // 用一句自然的话：TavernLab 模式下这会真实写进角色的聊天记录
            val probe = "在吗？"
            val reply = when (e.llmMode) {
                LlmMode.TAVERNLAB -> LlmClient.streamText(e.baseUrl, e.apiKey, e.model, probe, onDelta)
                LlmMode.OPENAI -> LlmClient.streamChat(
                    e.baseUrl, e.apiKey, e.model, listOf(LlmClient.Msg("user", probe)), onDelta
                )
            }
            val secs = if (ttft >= 0) "%.1f".format(ttft / 1000.0) else "?"
            val via = if (e.llmMode == LlmMode.TAVERNLAB) "TavernLab" else "OpenAI"
            Result(true, "OK（$via）· 首字 ${secs}s · ${reply.trim().take(40)}")
        } catch (ex: Exception) {
            Result(false, ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun testAsr(e: Endpoint, timeoutSec: Int = 90): Result {
        if (e.baseUrl.isBlank()) return Result(false, "Base URL 为空")
        return try {
            // 传一段 1s 静音，只看接口是否可用（不需要真的说话）
            val silence = Wav.fromPcm16(ShortArray(16000), 16000, 16000)
            val (code, body) = AsrClient.probe(
                e.baseUrl, e.apiKey, e.model, silence,
                readTimeoutMs = timeoutSec.coerceIn(10, 3600) * 1000,
            )
            if (code in 200..299) {
                val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
                Result(true, "连通 OK ${if (text.isBlank()) "(静音无文本)" else "→ ${text.take(30)}"}")
            } else {
                Result(false, "HTTP $code · ${body.take(140)}")
            }
        } catch (ex: Exception) {
            Result(false, ex.message ?: ex.javaClass.simpleName)
        }
    }

    fun testTts(e: Endpoint, timeoutSec: Int = 90): Result {
        if (e.baseUrl.isBlank()) return Result(false, "Base URL 为空")
        if (e.model.isBlank()) return Result(false, "Model 为空")
        return try {
            // 真的合成一句：设置页拿 audio 直接播出来试听
            val audio = TtsClient.synthesize(
                e.baseUrl, e.apiKey, e.model, e.voice, TTS_PROBE_TEXT,
                readTimeoutMs = timeoutSec.coerceIn(10, 3600) * 1000,
            )
            val kind = audio.contentType.ifBlank { "audio/?" }
            Result(true, "OK · ${audio.bytes.size / 1024} KB · $kind", audio)
        } catch (ex: Exception) {
            Result(false, ex.message ?: ex.javaClass.simpleName)
        }
    }

    /** 拉取 `GET /v1/models`，用于填 model id。 */
    fun listModels(base: String, key: String): Models {
        if (base.isBlank()) return Models(false, emptyList(), "Base URL 为空")
        return try {
            val url = Http.endpoint(base, "models", "/models")
            val (code, body) = Http.retryOnce {
                val conn = Http.open(url, key, readTimeoutMs = 20_000)
                try {
                    val c = conn.responseCode
                    c to Http.readText(if (c in 200..299) conn.inputStream else conn.errorStream)
                } finally {
                    Http.close(conn)
                }
            }
            if (code !in 200..299) return Models(false, emptyList(), "HTTP $code · ${body.take(140)}")
            val arr: JSONArray = runCatching {
                JSONObject(body).optJSONArray("data") ?: JSONArray()
            }.getOrDefault(JSONArray())
            val ids = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val id = arr.optJSONObject(i)?.optString("id").orEmpty()
                if (id.isNotBlank()) ids.add(id)
            }
            if (ids.isEmpty()) Models(false, emptyList(), "没有模型（响应无 data[].id）")
            else Models(true, ids, "共 ${ids.size} 个")
        } catch (ex: Exception) {
            Models(false, emptyList(), ex.message ?: ex.javaClass.simpleName)
        }
    }
}
