package com.roychong.deskspirit.conversation

/**
 * 口播清洗：把角色回复里的「动作 / 情绪描写、markdown、emoji、颜文字」剥掉，
 * 只留下真正能念出来的台词，交给 TTS。
 *
 * 屏幕上的 `tvReply` 仍显示原文（动作描写是角色魅力的一部分），
 * 只有送进 TTS 的那一份被清洗。
 *
 * 角色卡（以及 Gemini 风格的模型）常用这几种写法包住「舞台提示」：
 *
 *     （*她轻轻笑了笑*）     全角括号 + 星号
 *     *她轻轻笑了笑*         星号
 *     （她轻轻笑了笑）       全角括号
 *     (¬‿¬)                 半角括号里的颜文字
 *
 * 这些都要丢掉。但注意：`**加粗**` / `_斜体_` 里的**往往是台词本体**
 * （角色习惯把最重要的那句话加粗），所以只能「脱壳保留文字」，绝不能连文字一起删。
 */
object SpeechText {

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val CODE_INLINE = Regex("`[^`]*`")

    /** 分隔线：--- / *** / ___ */
    private val HR = Regex("(?m)^[ \\t]*[-*_]{3,}[ \\t]*$")

    /** 必须先脱 **加粗**（保留台词），再丢 *动作*。顺序反了就会把台词删掉。 */
    private val BOLD = Regex("\\*\\*([^*\\n]+)\\*\\*")
    private val ACTION_STAR = Regex("\\*([^*\\n]+)\\*")
    private val ITALIC = Regex("(?<![\\w])_([^_\\n]+)_(?![\\w])")

    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")

    /** 括号里的舞台提示（全角 / 半角，含 `（*...*）` 形态）。 */
    private val CJK_PAREN = Regex("（[^（）]*）")
    private val ASCII_PAREN = Regex("\\([^()]*\\)")

    private val MD_HEADING = Regex("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*")
    private val MD_QUOTE = Regex("(?m)^[ \\t]{0,3}[>•][ \\t]+")
    private val MD_BULLET = Regex("(?m)^[ \\t]{0,3}[-*+][ \\t]+")
    private val MD_NUMBER = Regex("(?m)^[ \\t]{0,3}\\d+[.、)）][ \\t]*")

    /** 箭头直接念出来很怪，换成顿号。 */
    private val ARROW = Regex("\\s*(?:→|⇒|->|-->)\\s*")

    /**
     * 白名单：只保留「汉字、拉丁字母数字、中日韩标点、全角标点、通用标点、换行」。
     * 其余字符（emoji、颜文字碎片、装饰符号）一律丢掉。
     *
     * 比逐个枚举 emoji 区间可靠得多，也顺带清掉 `ﾉ` `▽` `✧` `•` 这类残留。
     */
    private val NON_SPEECH = Regex(
        "[^" +
            "\\u4E00-\\u9FFF" +   // 汉字
            "\\u3400-\\u4DBF" +   // 汉字扩展 A
            "\\uF900-\\uFAFF" +   // 兼容汉字
            "\\u3001-\\u303F" +   // 中日韩标点（、。〈〉《》「」『』【】〜）
            "\\uFF01-\\uFF5E" +   // 全角标点（！＂…～）
            "\\u2000-\\u206F" +   // 通用标点（— … ‘’“”）
            "\\u0020-\\u007E" +   // ASCII 可打印
            "\\u00A0-\\u00FF" +   // 拉丁补充
            "\\n" +
            "]"
    )

    private val SPACES = Regex("[ \\t\\u3000]+")
    private val BLANK_LINES = Regex("(?m)^[ \\t]+$")
    private val NEWLINES = Regex("\\n{2,}")

    /** 整行只剩标点（清洗后留下的残渣），丢掉。 */
    private val PUNCT_ONLY_LINE =
        Regex("(?m)^[^\\u4E00-\\u9FFF\\u3400-\\u4DBFa-zA-Z0-9]+$")

    private val EDGE_JUNK = Regex(
        "^[\\s\"'“”‘’「」『』()（）\\[\\]【】、,，.。:：;；!！?？~～\\-—*#]+"
    )

    /** 清洗后可能为空（整段都是动作描写），调用方需要兜底。 */
    fun forSpeech(raw: String): String {
        var s = raw
        s = CODE_BLOCK.replace(s, " ")
        s = CODE_INLINE.replace(s, " ")
        s = HR.replace(s, " ")
        s = BOLD.replace(s, "$1")        // 台词脱壳，保留
        s = ACTION_STAR.replace(s, " ")  // *动作* 丢掉
        s = ITALIC.replace(s, "$1")
        s = MD_LINK.replace(s, "$1")
        s = CJK_PAREN.replace(s, " ")    // （描写）丢掉
        s = ASCII_PAREN.replace(s, " ")  // (颜文字/描写) 丢掉
        s = MD_HEADING.replace(s, "")
        s = MD_QUOTE.replace(s, "")
        s = MD_BULLET.replace(s, "")
        s = MD_NUMBER.replace(s, "")
        s = ARROW.replace(s, "、")
        s = NON_SPEECH.replace(s, " ")
        s = SPACES.replace(s, " ")
        s = PUNCT_ONLY_LINE.replace(s, "")
        s = BLANK_LINES.replace(s, "")
        s = NEWLINES.replace(s, "\n")
        s = EDGE_JUNK.replace(s, "")
        return s.trim()
    }
}
