package com.roychong.deskspirit.conversation

/**
 * 口播清洗：把角色回复里的「舞台描写 / markdown / emoji / 颜文字」剥掉，
 * 只留能念出来的台词，交给 TTS。
 *
 * 屏幕上仍然显示原文（动作描写是角色魅力的一部分），只有送进 TTS 的那一份被清洗。
 *
 * 例：
 *   （*她轻轻凑近，笑了笑。*）"**早安呀，我的大架构师！**" (๑•̀ㅂ•́)و✧
 *   → 早安呀，我的大架构师！
 */
object SpeechText {

    // 1) 代码
    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val CODE_INLINE = Regex("`[^`]*`")

    // 2) 强调：**粗体** / *斜体或动作* / _斜体_
    private val BOLD = Regex("\\*\\*[^*\\n]*\\*\\*")
    private val ASTERISK_SPAN = Regex("\\*[^*\\n]*\\*")
    private val UNDERSCORE = Regex("(?<![\\w])_[^_\\n]+_(?![\\w])")

    // 3) 括号旁白（中文全角 / 英文半角；角色最爱的 （*...*） 也会落到这里）
    private val PAREN = Regex("[（(][^（）()]*[）)]")

    // 4) markdown 结构
    private val MD_LINK = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val MD_HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s*")
    private val MD_QUOTE_LIST = Regex("(?m)^\\s{0,3}[>\\-*+•]\\s+")

    // 5) emoji / 颜文字点缀 / 装饰符号
    private val EMOJI = Regex(
        "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF" + // 代理对（emoji）
            "\\u2600-\\u27BF" +             // 杂项符号、装饰
            "\\u2B00-\\u2BFF" +             // 杂项符号与箭头
            "\\uFE0F\\u200D" +              // 变体选择符 / 零宽连接符
            "\\u2022\\u00B7\\u30FB\\uFF65]" // 圆点、间隔符等点缀
    )

    // 6) 空白与清洗后残留的孤立标点
    private val SPACES = Regex("[ \\t\\u3000]+")
    private val NEWLINES = Regex("\\n{2,}")
    private val LEADING_JUNK = Regex(
        "^[\\s\"'“”‘’「」『』()（）\\[\\]【】、,，.。:：;；!！?？~～\\-—*#]+"
    )
    private val TRAILING_WS = Regex("[\\s]+$")

    /** 清洗后可能为空（整段都是动作描写），调用方需要兜底。 */
    fun forSpeech(raw: String): String {
        var s = raw
        s = CODE_BLOCK.replace(s, " ")
        s = CODE_INLINE.replace(s, " ")
        s = BOLD.replace(s, " ")
        s = ASTERISK_SPAN.replace(s, " ")
        s = UNDERSCORE.replace(s, " ")
        s = MD_LINK.replace(s, "$1")
        s = PAREN.replace(s, " ")
        s = MD_HEADING.replace(s, "")
        s = MD_QUOTE_LIST.replace(s, "")
        s = EMOJI.replace(s, " ")
        s = SPACES.replace(s, " ")
        s = NEWLINES.replace(s, "\n")
        s = LEADING_JUNK.replace(s, "")
        s = TRAILING_WS.replace(s, "")
        return s.trim()
    }
}
