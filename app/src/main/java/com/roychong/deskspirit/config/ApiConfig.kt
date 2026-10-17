package com.roychong.deskspirit.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** LLM 后端形态。 */
enum class LlmMode {
    /** TavernLab：只发这一句新消息，上下文/角色卡/RAG 记忆全在服务端。 */
    TAVERNLAB,

    /** 通用 OpenAI 兼容端点：由客户端自己带 messages 历史。 */
    OPENAI;

    val storageKey: String get() = if (this == OPENAI) "openai" else "tavernlab"

    companion object {
        fun of(key: String?) = if (key == "openai") OPENAI else TAVERNLAB
    }
}

/** 服务槽位：每个槽位独立维护一组命名端点（profile），并可各自选一个当前使用。 */
enum class Slot(val storeKey: String, val label: String) {
    LLM_DIALOG("llm", "Main"),
    LLM_TOOL("tool_llm", "工具 LLM（整理）"),
    ASR("asr", "ASR 语音识别"),
    TTS("tts", "TTS 语音合成");

    /** LLM 槽位才有 TavernLab 开关。 */
    val isLlm: Boolean get() = this == LLM_DIALOG || this == LLM_TOOL

    /** 只有 TTS 需要 voice。 */
    val needsVoice: Boolean get() = this == TTS

    companion object {
        fun of(key: String?): Slot? = values().firstOrNull { it.storeKey == key }
    }
}

/** 一个命名端点（profile）。 */
data class Endpoint(
    val name: String = "默认",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val voice: String = "",
    val llmMode: LlmMode = LlmMode.TAVERNLAB,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("base", baseUrl)
        .put("key", apiKey)
        .put("model", model)
        .put("voice", voice)
        .put("mode", llmMode.storageKey)

    companion object {
        fun fromJson(o: JSONObject): Endpoint = Endpoint(
            name = o.optString("name").ifBlank { "默认" },
            baseUrl = o.optString("base"),
            apiKey = o.optString("key"),
            model = o.optString("model"),
            voice = o.optString("voice"),
            llmMode = LlmMode.of(o.optString("mode")),
        )
    }
}

/**
 * 用 SharedPreferences 存配置：每个 [Slot] 一组命名端点 + 当前选中下标。
 *
 * v0.1 是「单套配置」，这里在首次读取新格式时把旧值迁移成名为「默认」的 profile，
 * 用户不用重填。Key 仍是明文，后续可换 EncryptedSharedPreferences。
 */
class ApiConfig(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    // ---------------- 读 ----------------

    fun profiles(slot: Slot): List<Endpoint> {
        val raw = sp.getString(profilesKey(slot), null)
        if (raw.isNullOrBlank()) return listOf(defaultFor(slot))
        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return listOf(defaultFor(slot))
        val list = ArrayList<Endpoint>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(Endpoint.fromJson(it)) }
        }
        return list.ifEmpty { listOf(defaultFor(slot)) }
    }

    fun activeIndex(slot: Slot): Int {
        val n = profiles(slot).size
        val i = sp.getInt(activeKey(slot), 0)
        return i.coerceIn(0, (n - 1).coerceAtLeast(0))
    }

    fun active(slot: Slot): Endpoint = profiles(slot)[activeIndex(slot)]

    // 便利方法
    fun llm() = active(Slot.LLM_DIALOG)
    fun toolLlm() = active(Slot.LLM_TOOL)
    fun asr() = active(Slot.ASR)
    fun tts() = active(Slot.TTS)
    fun llmMode(): LlmMode = llm().llmMode

    // ---------------- 写 ----------------

    fun save(slot: Slot, list: List<Endpoint>, activeIndex: Int) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val active = activeIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
        sp.edit()
            .putString(profilesKey(slot), arr.toString())
            .putInt(activeKey(slot), active)
            .apply()
    }

    // ---------------- 默认值与迁移 ----------------

    private fun profilesKey(slot: Slot) = "profiles_${slot.storeKey}"
    private fun activeKey(slot: Slot) = "active_${slot.storeKey}"

    /** 全新安装时的默认：直接指向本机 LAN 的 ggml 三件套，开箱即用。 */
    private fun defaultFor(slot: Slot): Endpoint = when (slot) {
        Slot.LLM_DIALOG -> Endpoint(
            name = "本地 llama.cpp",
            baseUrl = LAN_LLM, model = "local", llmMode = LlmMode.OPENAI
        )

        Slot.LLM_TOOL -> Endpoint(
            name = "本地 llama.cpp",
            baseUrl = LAN_LLM, model = "local", llmMode = LlmMode.OPENAI
        )

        Slot.ASR -> Endpoint(name = "本地 speech.cpp", baseUrl = LAN_ASR, model = "asr")
        Slot.TTS -> Endpoint(
            name = "本地 audio.cpp", baseUrl = LAN_TTS,
            model = "qwen3-tts", voice = "serena"
        )
    }

    /**
     * 旧版单套配置 → 新的 profile 结构。只在旧 key 存在且新结构尚未写过时执行。
     */
    private fun migrateLegacyIfNeeded() {
        val hasLegacy = sp.contains(K_LEGACY_LLM_BASE) || sp.contains(K_LEGACY_ASR_BASE) ||
            sp.contains(K_LEGACY_TTS_BASE)
        val alreadyMigrated = sp.contains(profilesKey(Slot.LLM_DIALOG))
        if (!hasLegacy || alreadyMigrated) return

        val llm = Endpoint(
            name = "默认",
            baseUrl = g(K_LEGACY_LLM_BASE),
            apiKey = g(K_LEGACY_LLM_KEY),
            model = g(K_LEGACY_LLM_MODEL),
            llmMode = if (g(K_LEGACY_LLM_MODE) == "openai") LlmMode.OPENAI else LlmMode.TAVERNLAB,
        )
        val asr = Endpoint(
            name = "默认",
            baseUrl = g(K_LEGACY_ASR_BASE),
            apiKey = g(K_LEGACY_ASR_KEY),
            model = g(K_LEGACY_ASR_MODEL),
        )
        val tts = Endpoint(
            name = "默认",
            baseUrl = g(K_LEGACY_TTS_BASE),
            apiKey = g(K_LEGACY_TTS_KEY),
            model = g(K_LEGACY_TTS_MODEL),
            voice = g(K_LEGACY_TTS_VOICE),
        )

        save(Slot.LLM_DIALOG, listOf(llm), 0)
        // 工具 LLM 旧版没有：整理必须走普通 OpenAI 兼容端点，
        // 复制 TavernLab 那份会直接报错，所以给本地 llama.cpp 默认值。
        save(Slot.LLM_TOOL, listOf(defaultFor(Slot.LLM_TOOL)), 0)
        save(Slot.ASR, listOf(asr), 0)
        save(Slot.TTS, listOf(tts), 0)
    }

    private fun g(k: String): String = sp.getString(k, "") ?: ""

    companion object {
        private const val PREFS = "deskspirit_api"

        // 本机 LAN 默认端点（可在设置里改）
        private const val LAN_LLM = "http://192.168.100.200:12888/v1"
        private const val LAN_ASR = "http://192.168.100.200:11438/v1"
        private const val LAN_TTS = "http://192.168.100.200:11439/v1"

        // 旧版 key
        private const val K_LEGACY_LLM_BASE = "llm_base"
        private const val K_LEGACY_LLM_KEY = "llm_key"
        private const val K_LEGACY_LLM_MODEL = "llm_model"
        private const val K_LEGACY_LLM_MODE = "llm_mode"

        private const val K_LEGACY_ASR_BASE = "asr_base"
        private const val K_LEGACY_ASR_KEY = "asr_key"
        private const val K_LEGACY_ASR_MODEL = "asr_model"

        private const val K_LEGACY_TTS_BASE = "tts_base"
        private const val K_LEGACY_TTS_KEY = "tts_key"
        private const val K_LEGACY_TTS_MODEL = "tts_model"
        private const val K_LEGACY_TTS_VOICE = "tts_voice"
    }
}
