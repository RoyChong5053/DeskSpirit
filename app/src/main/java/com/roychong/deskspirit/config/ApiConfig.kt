package com.roychong.deskspirit.config

import android.content.Context

/** LLM 后端形态。 */
enum class LlmMode {
    /** TavernLab：只发这一句新消息，上下文/角色卡/RAG 记忆全在服务端。 */
    TAVERNLAB,

    /** 通用 OpenAI 兼容端点：由客户端自己带 messages 历史。 */
    OPENAI
}

/** 一个接口的配置（OpenAI 兼容形态）。 */
data class ApiEndpoint(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/** 用 SharedPreferences 存 API 配置。v0.1 够用，后续可换 EncryptedSharedPreferences。 */
class ApiConfig(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun llm() = ApiEndpoint(g(K_LLM_BASE), g(K_LLM_KEY), g(K_LLM_MODEL))
    fun asr() = ApiEndpoint(g(K_ASR_BASE), g(K_ASR_KEY), g(K_ASR_MODEL))
    fun tts() = ApiEndpoint(g(K_TTS_BASE), g(K_TTS_KEY), g(K_TTS_MODEL), g(K_TTS_VOICE))

    fun llmMode(): LlmMode =
        if (g(K_LLM_MODE) == MODE_OPENAI) LlmMode.OPENAI else LlmMode.TAVERNLAB

    fun saveLlm(base: String, key: String, model: String, mode: LlmMode) = sp.edit()
        .putString(K_LLM_BASE, base.trim())
        .putString(K_LLM_KEY, key.trim())
        .putString(K_LLM_MODEL, model.trim())
        .putString(
            K_LLM_MODE,
            if (mode == LlmMode.OPENAI) MODE_OPENAI else MODE_TAVERNLAB
        )
        .apply()

    fun saveAsr(base: String, key: String, model: String) = sp.edit()
        .putString(K_ASR_BASE, base.trim())
        .putString(K_ASR_KEY, key.trim())
        .putString(K_ASR_MODEL, model.trim())
        .apply()

    fun saveTts(base: String, key: String, model: String, voice: String) = sp.edit()
        .putString(K_TTS_BASE, base.trim())
        .putString(K_TTS_KEY, key.trim())
        .putString(K_TTS_MODEL, model.trim())
        .putString(K_TTS_VOICE, voice.trim())
        .apply()

    private fun g(k: String): String = sp.getString(k, "") ?: ""

    companion object {
        private const val PREFS = "deskspirit_api"

        private const val K_LLM_BASE = "llm_base"
        private const val K_LLM_KEY = "llm_key"
        private const val K_LLM_MODEL = "llm_model"
        private const val K_LLM_MODE = "llm_mode"

        private const val K_ASR_BASE = "asr_base"
        private const val K_ASR_KEY = "asr_key"
        private const val K_ASR_MODEL = "asr_model"

        private const val K_TTS_BASE = "tts_base"
        private const val K_TTS_KEY = "tts_key"
        private const val K_TTS_MODEL = "tts_model"
        private const val K_TTS_VOICE = "tts_voice"

        private const val MODE_TAVERNLAB = "tavernlab"
        private const val MODE_OPENAI = "openai"
    }
}
