package com.roychong.deskspirit.conversation

import com.roychong.deskspirit.config.Endpoint
import com.roychong.deskspirit.net.LlmClient

/**
 * 3-pass「整理」：把口述的 raw 识别文本交给**工具 LLM**（单独 profile）整理成通顺文字。
 * 用可编辑的 prompt 模板（system）。
 */
object TextRefiner {

    fun refine(
        tool: Endpoint,
        prompt: String,
        text: String,
        onDelta: (String) -> Unit = {},
    ): String {
        val messages = listOf(
            LlmClient.Msg("system", prompt),
            LlmClient.Msg("user", text),
        )
        return LlmClient.streamChat(tool.baseUrl, tool.apiKey, tool.model, messages, onDelta)
    }
}
