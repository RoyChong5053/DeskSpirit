package com.roychong.deskspirit.voice

import java.io.File

/** 语音库条目的来源。 */
enum class VoiceKind(val key: String, val label: String) {
    AI("ai", "AI 语音"),
    USER("user", "我的录音"),
    DICTATE("dictate", "口述");

    companion object {
        fun of(k: String?): VoiceKind = values().firstOrNull { it.key == k } ?: AI
    }
}

/**
 * 一条语音记录。音频可缺省（纯文本记录：口述时没留音频的情况）。
 * 元数据以同名 `.json` sidecar 存在音频旁边，脱离 App 也自解释。
 */
data class VoiceEntry(
    val id: String,
    val ts: Long,
    val text: String,
    val model: String,
    val voice: String,
    val provider: String,
    val kind: VoiceKind,
    val durationMs: Int,
    val favorite: Boolean,
    val contentType: String,
    val sizeBytes: Long,
    val audioFile: File?,
) {
    val hasAudio: Boolean get() = audioFile != null && audioFile.exists()
}
