package com.roychong.deskspirit.net

/** URL 拼接与通用 HTTP 小工具。 */
object Http {

    /**
     * 智能拼端点。用户可能填三种形态中的任意一种：
     *
     *  - 纯域名         `https://api.xxx.com`
     *  - 带 /v1         `https://api.xxx.com/v1`
     *  - 完整端点       `https://openrouter.ai/api/v1/audio/speech`
     *
     * @param marker 用来判断“已经是完整端点”的关键字，例如 "audio/speech"
     * @param path   需要补的路径，例如 "/audio/speech"
     */
    fun endpoint(base: String, marker: String, path: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (b.contains(marker)) return b
        return if (b.endsWith("/v1")) b + path else "$b/v1$path"
    }

    /** 完整读取（大量字节场景用流式，这里给短响应）。 */
    fun readText(stream: java.io.InputStream?): String =
        stream?.bufferedReader()?.use { it.readText() } ?: ""
}
