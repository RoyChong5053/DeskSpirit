package com.roychong.deskspirit.conversation

/**
 * 增量切句器：把 LLM 的流式输出切成「可以单独送 TTS 的片段」。
 *
 * 为什么需要它：原来的链路是「整段 LLM 全流完 → 整段送 TTS → 整段播」，
 * 角色回复 490–752 字（清洗后 315–538 字）时，要等 **60–95 秒**才开口。
 * 逐句切分后，第一句一出就能合成、播放，后面的句子在播放期间继续算。
 *
 * 几个关键取舍：
 *
 *  - **先切后洗**。切分在**原文**上做，切出来的每一片再走 [SpeechText.forSpeech]。
 *    清洗是整段正则（`**加粗**`、代码块、`（舞台提示）`），按 delta 增量洗会把跨边界的
 *    标记切坏，所以必须等一片完整了再洗。
 *  - **首片要小**。[firstMax] 只用来判断「要不要在一个逗号处先切一刀」，
 *    目的是把**开口时间**压到最短；后续片攒到 [MIN]..[max] 再切，
 *    片长更均匀，少发几次请求、句间语调也更稳。
 *  - **不在标记中间切**。[safe] 要求括号/星号/反引号在切点前都成对，
 *    否则 `**强调**`、`（描写）` 会被从中间劈开，洗出来一个残废句。
 *  - **片长有硬上限**。万一模型吐一大段没有标点的文字（或者某个标记一直不闭合），
 *    到 [HARD_LIMIT] 也必须强切，否则永远开不了口。
 *
 * 切分是纯粹的本地文本处理，**不依赖任何 TTS 后端**：切出来的每一片走的还是
 * 原来那条 `POST /audio/speech`，所以对任意 OpenAI 兼容 TTS 都成立。
 *
 * @param chunked false 时退化成「整段一片」（关掉逐句播报，行为和旧版一致）
 */
class SpeechSegmenter(
    /** 首片的最大字数（决定多久开口）。 */
    private val firstMax: Int = FIRST_MAX,
    /** 后续每片的最大字数。 */
    private val max: Int = MAX,
    /** 是否真的切分；false = 攒成一整段再发（旧行为）。 */
    private val chunked: Boolean = true,
    /** 口播清洗器；测试可注入。 */
    private val clean: (String) -> String = SpeechText::forSpeech,
) {
    /** 一片定稿的口播文本。index 从 0 连续递增，界面的 KTV 字幕按它对齐。 */
    data class Segment(val index: Int, val text: String)

    /** 还没定稿的原文尾巴。 */
    private val raw = StringBuilder()

    /** 下一片的序号。 */
    private var next = 0

    /** 喂一段 LLM 增量，返回这次能定稿的片段（通常 0 或 1 个）。 */
    fun feed(delta: String): List<Segment> {
        if (delta.isEmpty()) return emptyList()
        raw.append(delta)
        return drain(force = false)
    }

    /** LLM 输出结束：把尾巴也吐出来。 */
    fun finish(): List<Segment> = drain(force = true)

    private fun drain(force: Boolean): List<Segment> {
        val out = ArrayList<Segment>(1)
        while (true) {
            val cut = nextCut(force)
            if (cut <= 0) break
            val piece = raw.substring(0, cut)
            raw.delete(0, cut)
            val cleaned = clean(piece).trim()
            // 洗成空（整片是动作描写/代码块/emoji）就丢掉，但要占掉一次剪切，
            // 否则会卡在原地反复扫同一段。
            if (cleaned.isNotEmpty()) out.add(Segment(next++, cleaned))
        }
        return out
    }

    /** 返回下一个切点（不含），0 表示「还不到切的时候」。 */
    private fun nextCut(force: Boolean): Int {
        val n = raw.length
        if (n == 0) return 0
        if (!chunked) return if (force) n else 0
        return if (next == 0) firstCut(n, force) else steadyCut(n, force)
    }

    /** 首片：见到第一个完整句末就切，目标是**尽快开口**，所以不攒长度。 */
    private fun firstCut(n: Int, force: Boolean): Int {
        var i = 0
        while (i < n) {
            if (isStrong(raw[i])) {
                val end = extendTrailing(i + 1)
                if (safe(end)) return end
            }
            i++
        }
        if (force) return if (n <= max) n else steadyCut(n, true)
        val limit = firstMax
        if (n < limit) return 0
        var j = limit - 1
        while (j > 0) {
            if (isWeak(raw[j]) && safe(j + 1)) return j + 1
            j--
        }
        return if (safe(limit)) limit else 0
    }

    /**
     * 后续片：把 [MIN, max] 里的整句**攒成一片**再切。
     *
     * 以前是"见到第一个句号就切"，结果片长在 6–37 字之间乱跳：一句一请求
     * 既浪费合成时间，句与句之间的语调/音量也容易忽大忽小。现在同一个
     * 窗口内有多句就取**最后**一个句末，片长稳定在 40–80 字左右。
     *
     * force（LLM 输出结束）时也要按这个上限拆，不能把 150 多字的尾巴
     * 一次性丢给 TTS —— 那会让最后一段明显变慢、也破坏节奏。
     */
    private fun steadyCut(n: Int, force: Boolean): Int {
        if (!force && n < MIN) return 0
        val lo = if (force) 0 else MIN

        // 1) 强边界：窗口 [lo, max] 内取**最后一个**完整句末；
        //    同时记住窗口外第一个安全句末（firstOut），作为"结构性卡住"时的逃生口。
        var lastIn = 0
        var firstOut = 0
        var i = lo
        while (i < n) {
            if (isStrong(raw[i])) {
                val end = extendTrailing(i + 1)
                if (safe(end)) {
                    if (end <= max) {
                        lastIn = end
                    } else {
                        firstOut = end
                        break
                    }
                }
                i = if (end > i) end else i + 1
                continue
            }
            i++
        }
        // 窗口没攒满就先不切（除非已经收尾）。这样片长才会稳定在 60–80 字，
        // 而不是"LLM 吐一句就切一句"，又回到 17 字一请求的碎节奏。
        if (lastIn > 0 && (force || n >= max)) return lastIn

        // 2) 还没攒到 max：继续等（攒得越满，片长越均匀）
        if (!force && n < max) return 0

        // 3) 窗口内没有句末就退到逗号/顿号（同样取窗口内最后一个）
        for (j in minOf(max, n) - 1 downTo maxOf(lo, 1)) {
            if (isWeak(raw[j]) && safe(j + 1)) return j + 1
        }

        // 4) 窗口内一个安全切点都没有（长括号描写 / 代码块还没闭合 / 杂散的 *）：
        //    宁可稍微超长，也不能把两百多字攒成一片 —— 那一声会明显卡住。
        if (firstOut > 0 && firstOut <= HARD_LIMIT) return firstOut

        // 5) 兜底
        if (n >= HARD_LIMIT) return hardCut()
        if (force && n <= max) return n
        return if (safe(minOf(max, n))) minOf(max, n) else if (force) hardCut() else 0
    }

    /**
     * 句末符号后面往往还有收尾引号/括号，或者重复的句末符号（`……` `！！` `。。。`），
     * 一起带进这一片，念出来才完整。**不吃换行**：换行是段落/列表项的分界。
     */
    private fun extendTrailing(from: Int): Int {
        var e = from
        while (e < raw.length) {
            val c = raw[e]
            if (c == '\n' || (!TRAILING.contains(c) && !STRONG.contains(c))) break
            e++
        }
        return e
    }

    /**
     * 切点是否「干净」：切完之后不能还停在某个标记**里面**。
     *
     * 光数"成对"是不够的：`**先把这杯喝了。` 里 `*` 也是偶数个，
     * 但它明明是把加粗的开头切出来了（后面会多出一个孤零零的 `**`）。
     * 所以这里跑一个小状态机，只有"加粗/斜体/行内码/围栏/括号全都闭合"才放行。
     *
     * 但标记如果**开了整整一个窗口还没闭合**，那基本是模型漏写、或者
     * 列表项前面那种落单的 `*`、没配对的括号。再让它挡切点，就会把几百字
     * 攒成一片（实测出现过 230 字／24 秒）。所以只拦「刚打开、还没闭合」的标记。
     */
    private fun safe(end: Int): Boolean {
        var bold = -1
        var italic = -1
        var tick = -1
        var fence = -1
        var paren = 0
        var parenAt = -1
        var i = 0
        while (i < end) {
            when (raw[i]) {
                '`' -> if (i + 2 < end && raw[i + 1] == '`' && raw[i + 2] == '`') {
                    fence = if (fence < 0) i else -1
                    i += 3
                } else {
                    tick = if (tick < 0) i else -1
                    i++
                }
                '*' -> if (i + 1 < end && raw[i + 1] == '*') {
                    bold = if (bold < 0) i else -1
                    i += 2
                } else {
                    italic = if (italic < 0) i else -1
                    i++
                }
                '（', '(' -> {
                    if (paren == 0) parenAt = i
                    paren++
                    i++
                }
                '）', ')' -> {
                    // 只减到 0：多出来的右括号（颜文字残留）不能把后面的切点全毒死
                    if (paren > 0) paren--
                    if (paren == 0) parenAt = -1
                    i++
                }
                else -> i++
            }
        }
        val stale = end - max
        fun openAt(p: Int) = p in 0 until end && p > stale
        return !openAt(bold) && !openAt(italic) && !openAt(tick) &&
            !openAt(fence) && !(paren > 0 && openAt(parenAt))
    }

    /** 硬上限兜底：尽量贴在最后一个弱边界上，实在没有就在上限处直接切。 */
    private fun hardCut(): Int {
        var j = minOf(HARD_LIMIT, raw.length) - 1
        while (j > 0) {
            if (isWeak(raw[j])) return j + 1
            j--
        }
        return minOf(HARD_LIMIT, raw.length)
    }

    companion object {
        /** 首片上限：约 2.5–6 秒语音，够快开口又不至于碎。 */
        const val FIRST_MAX = 30

        /** 后续片的下限：短于它的句子先攒着，和下一句合成一片再送。 */
        const val MIN = 16

        /** 后续每片上限：约 10–18 秒语音，请求数和韵律的折中。 */
        const val MAX = 80

        /** 绝对上限：超过它就不再等边界了（见类注释）。 */
        const val HARD_LIMIT = 320

        private val STRONG = charArrayOf('。', '！', '？', '!', '?', '…', '\n')
        private val WEAK = charArrayOf('，', '、', '；', '：', ',', ';', ':')
        private val TRAILING = charArrayOf('”', '’', '」', '』', '》', '〉', '】', '）', ')', '"', '\'')

        private fun isStrong(c: Char) = STRONG.contains(c)
        private fun isWeak(c: Char) = WEAK.contains(c)
    }
}
