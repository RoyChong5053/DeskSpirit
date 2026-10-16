package com.roychong.deskspirit.net

import com.roychong.deskspirit.audio.Wav
import com.roychong.deskspirit.config.LlmMode
import org.json.JSONObject

/**
 * 设置页的连通性测试。三个接口都按真实形态调用，
 * 所以“测试通过 = 主界面那一轮也能跑通”。
 */
object ApiTester {

    data class Result(val ok: Boolean, val message: String)

    fun testLlm(base: String, key: String, model: String, mode: LlmMode): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        if (model.isBlank()) return Result(false, "Model 为空")
        return try {
            val t0 = System.currentTimeMillis()
            var ttft = -1L
            val onDelta: (String) -> Unit = {
                if (ttft < 0) ttft = System.currentTimeMillis() - t0
            }
            // 用一句自然的话：TavernLab 模式下这会真实写进角色的聊天记录
            val probe = "在吗？"
            val reply = when (mode) {
                LlmMode.TAVERNLAB -> LlmClient.streamText(base, key, model, probe, onDelta)
                LlmMode.OPENAI -> LlmClient.streamChat(
                    base, key, model, listOf(LlmClient.Msg("user", probe)), onDelta
                )
            }
            val secs = if (ttft >= 0) "%.1f".format(ttft / 1000.0) else "?"
            val via = if (mode == LlmMode.TAVERNLAB) "TavernLab" else "OpenAI"
            Result(true, "OK（$via）· 首字 ${secs}s · ${reply.trim().take(40)}")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun testAsr(base: String, key: String, model: String): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        return try {
            // 传一段 1s 静音，只看接口是否可用（不需要真的说话）
            val silence = Wav.fromPcm16(ShortArray(16000), 16000, 16000)
            val (code, body) = AsrClient.probe(base, key, model, silence)
            if (code in 200..299) {
                val text = runCatching { JSONObject(body).optString("text") }.getOrDefault("")
                Result(true, "连通 OK ${if (text.isBlank()) "(静音无文本)" else "→ ${text.take(30)}"}")
            } else {
                Result(false, "HTTP $code · ${body.take(140)}")
            }
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun testTts(base: String, key: String, model: String, voice: String): Result {
        if (base.isBlank()) return Result(false, "Base URL 为空")
        if (model.isBlank()) return Result(false, "Model 为空")
        return try {
            val audio = TtsClient.synthesize(base, key, model, voice, "你好，我是 DeskSpirit。")
            val kind = audio.contentType.ifBlank { "audio/?" }
            Result(true, "OK · ${audio.bytes.size / 1024} KB · $kind")
        } catch (e: Exception) {
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
