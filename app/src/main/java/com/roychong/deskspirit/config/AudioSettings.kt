package com.roychong.deskspirit.config

import android.content.Context

/** 收音（VAD）参数。全部可调，见设置页「收音」。 */
data class VadParams(
    /** 说完后静音多久算一句结束。 */
    val endSilenceMs: Int = 900,
    /** 绝对门限（RMS）：环境底噪一般远低于它。 */
    val minLevel: Float = 0.012f,
    /** 相对门限：比噪声地板高这么多倍才算有人说话。 */
    val voiceRatio: Float = 3.0f,
    /** 最长一句话，超了强制切。 */
    val maxSpeechMs: Int = 15000,
    /** 一段话至少要有这么多毫秒是"真·有声"。 */
    val minVoicedMs: Int = 320,
    /** 有声帧占整段的比例下限。 */
    val minVoicedRatio: Float = 0.30f,
    /** 太短的整段直接丢掉。 */
    val minSpeechMs: Int = 250,
)

/** 口述（2-pass）参数。 */
data class DictationParams(
    /** 口述最长时长，到点自动停止并识别。 */
    val maxMs: Int = 120_000,
)

/**
 * 本地交互参数（收音 / 口述 / 语音库 / 整理 prompt）。
 * 与 API 配置分开存，逻辑上互不相干。
 */
class AudioSettings(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun vad() = VadParams(
        endSilenceMs = sp.getInt(KV_END_SILENCE, 900),
        minLevel = sp.getFloat(KV_MIN_LEVEL, 0.012f),
        voiceRatio = sp.getFloat(KV_VOICE_RATIO, 3.0f),
        maxSpeechMs = sp.getInt(KV_MAX_SPEECH, 15000),
        minVoicedMs = sp.getInt(KV_MIN_VOICED, 320),
        minVoicedRatio = sp.getFloat(KV_MIN_RATIO, 0.30f),
        minSpeechMs = sp.getInt(KV_MIN_SPEECH, 250),
    )

    fun saveVad(p: VadParams) = sp.edit()
        .putInt(KV_END_SILENCE, p.endSilenceMs)
        .putFloat(KV_MIN_LEVEL, p.minLevel)
        .putFloat(KV_VOICE_RATIO, p.voiceRatio)
        .putInt(KV_MAX_SPEECH, p.maxSpeechMs)
        .putInt(KV_MIN_VOICED, p.minVoicedMs)
        .putFloat(KV_MIN_RATIO, p.minVoicedRatio)
        .putInt(KV_MIN_SPEECH, p.minSpeechMs)
        .apply()

    fun dictation() = DictationParams(
        maxMs = sp.getInt(KD_MAX, 120_000),
    )

    fun saveDictation(p: DictationParams) = sp.edit()
        .putInt(KD_MAX, p.maxMs)
        .apply()

    // ---- ASR 等待上限 ----

    /**
     * 整段音频送到服务端后，最多等多久拿识别结果（秒）。
     *
     * 本地 CPU 跑大模型时远不止一分钟（同步接口在出结果前一个字节都不回，
     * 所以读超时就是"整段推理"的上限），需要能放宽；云端一般 90s 绰绰有余。
     */
    fun asrTimeoutSec(): Int = sp.getInt(KV_ASR_TIMEOUT, 90).coerceIn(10, 3600)

    fun saveAsrTimeoutSec(sec: Int) = sp.edit()
        .putInt(KV_ASR_TIMEOUT, sec.coerceIn(10, 3600))
        .apply()

    // ---- TTS 等待上限 ----

    /**
     * 合成请求最多等多久（秒）。本地 TTS 跑大模型时，长段落可能要几分钟，
     * 而服务端是「全部合成完才返回」，等待期间一个字节都不回。
     */
    fun ttsTimeoutSec(): Int = sp.getInt(KV_TTS_TIMEOUT, 90).coerceIn(10, 3600)

    fun saveTtsTimeoutSec(sec: Int) = sp.edit()
        .putInt(KV_TTS_TIMEOUT, sec.coerceIn(10, 3600))
        .apply()

    // ---- Live（常驻监听）----

    /**
     * Live 模式：开着就自动开麦、等 VAD 切句；关掉就完全不拾音（只剩口述页）。
     *
     * 这个值必须跨进程/重启保持：用户在嘈杂环境里关掉它，下次打开 App
     * 大概率还是同一个环境，不能一启动就自动开麦乱听。
     */
    fun liveEnabled(): Boolean = sp.getBoolean(KV_LIVE, true)

    fun saveLiveEnabled(on: Boolean) = sp.edit()
        .putBoolean(KV_LIVE, on)
        .apply()

    // ---- Live 自动整理 ----

    /**
     * Live 拾音出一条 ASR 文本后，是否先交给工具 LLM 整理再进对话。
     *
     * 云端强 ASR 一般不需要；本地 whisper / 小模型错字多，整理一下对话质量差很多。
     */
    fun liveRefine(): Boolean = sp.getBoolean(KV_LIVE_REFINE, false)

    fun saveLiveRefine(on: Boolean) = sp.edit()
        .putBoolean(KV_LIVE_REFINE, on)
        .apply()

    // ---- 语音库 ----

    /** 是否把 TTS 生成的语音存档。 */
    fun saveVoice(): Boolean = sp.getBoolean(KV_SAVE_VOICE, true)

    /** 是否把 Live 模式的用户录音也存档（方便核对 ASR）。 */
    fun saveUserAudio(): Boolean = sp.getBoolean(KV_SAVE_USER, false)

    /** 最多保留多少条（0 = 不限）。 */
    fun voiceMaxCount(): Int = sp.getInt(KV_MAX_COUNT, 200)

    /** 最多保留多少天（0 = 不限）。 */
    fun voiceMaxDays(): Int = sp.getInt(KV_MAX_DAYS, 30)

    fun saveVoicePrefs(save: Boolean, saveUser: Boolean, maxCount: Int, maxDays: Int) = sp.edit()
        .putBoolean(KV_SAVE_VOICE, save)
        .putBoolean(KV_SAVE_USER, saveUser)
        .putInt(KV_MAX_COUNT, maxCount)
        .putInt(KV_MAX_DAYS, maxDays)
        .apply()

    // ---- 界面外观 ----

    /** 运行时的圆球 preset（见 SphereStyle）。 */
    fun sphereStyle(): Int = sp.getInt(KS_SPHERE_STYLE, 0)

    fun saveSphereStyle(id: Int) = sp.edit()
        .putInt(KS_SPHERE_STYLE, id)
        .apply()

    // ---- TavernLab 当前角色 ----

    /**
     * 当前角色的**本地缓存**，只用于主界面显示。
     * 真源是 TavernLab 服务端的 `current_char`（网页与 App 共享），
     * 所以每次进设置页/切角色都会用服务端返回值刷新这里。
     */
    fun tavernChar(): String = sp.getString(KV_TAVERN_CHAR, "") ?: ""

    fun saveTavernChar(name: String) = sp.edit()
        .putString(KV_TAVERN_CHAR, name.trim())
        .apply()

    // ---- 整理 prompt ----

    fun refinePrompt(): String = sp.getString(KV_REFINE_PROMPT, DEFAULT_REFINE_PROMPT)
        ?: DEFAULT_REFINE_PROMPT

    fun saveRefinePrompt(s: String) = sp.edit()
        .putString(KV_REFINE_PROMPT, s.trim().ifBlank { DEFAULT_REFINE_PROMPT })
        .apply()

    companion object {
        private const val PREFS = "deskspirit_local"

        private const val KV_END_SILENCE = "vad_end_silence_ms"
        private const val KV_MIN_LEVEL = "vad_min_level"
        private const val KV_VOICE_RATIO = "vad_voice_ratio"
        private const val KV_MAX_SPEECH = "vad_max_speech_ms"
        private const val KV_MIN_VOICED = "vad_min_voiced_ms"
        private const val KV_MIN_RATIO = "vad_min_voiced_ratio"
        private const val KV_MIN_SPEECH = "vad_min_speech_ms"

        private const val KD_MAX = "dictation_max_ms"

        private const val KV_ASR_TIMEOUT = "asr_timeout_s"
        private const val KV_TTS_TIMEOUT = "tts_timeout_s"

        private const val KV_LIVE = "live_enabled"
        private const val KV_LIVE_REFINE = "live_refine"

        private const val KV_SAVE_VOICE = "voice_save_ai"
        private const val KV_SAVE_USER = "voice_save_user"
        private const val KV_MAX_COUNT = "voice_max_count"
        private const val KV_MAX_DAYS = "voice_max_days"

        private const val KV_REFINE_PROMPT = "refine_prompt"

        private const val KS_SPHERE_STYLE = "sphere_style"

        private const val KV_TAVERN_CHAR = "tavern_char"

        const val DEFAULT_REFINE_PROMPT =
            "把下面这段语音识别出来的口述整理成通顺的段落：纠正明显的识别错别字，" +
                "去掉口头重复和「呃、那个、就是」这类碎词，按意思分段。" +
                "保留原意与语气，不要添加原文没有的内容，不要总结或改写观点。只输出整理后的正文。"
    }
}
