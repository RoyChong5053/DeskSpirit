package com.roychong.deskspirit.voice

import android.content.Context
import android.util.Log
import com.roychong.deskspirit.audio.AudioPlayer
import com.roychong.deskspirit.audio.Spectrum
import com.roychong.deskspirit.audio.Wav
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * 语音库：把 TTS 生成（以及可选的我方录音 / 口述）落盘，方便重放、核对、外部导出。
 *
 * 目录优先用 `Android/media/<pkg>/DeskSpirit/voice/`（文件管理器可读、不需要任何权限），
 * 不可用时退回应用私有 `filesDir/voice/`。
 *
 * 每条 = 音频文件 + 同名 `.json` sidecar。PCM 会被包成 WAV，方便外部直接播放。
 */
class VoiceStore(context: Context) {

    private val app = context.applicationContext

    val dir: File = resolveDir()

    /** 目录是否落在外部可读位置（文件管理器能进去）。 */
    val external: Boolean get() = dir.absolutePath.contains("/Android/media/")

    private fun resolveDir(): File {
        val base = app.getExternalMediaDirs()?.firstOrNull { it != null }
        val d = if (base != null) File(base, "DeskSpirit/voice") else File(app.filesDir, "voice")
        if (!d.exists()) d.mkdirs()
        return d
    }

    // ---------------- 写 ----------------

    /**
     * 存一条记录。
     * @param bytes 音频字节；null 表示纯文本记录
     * @param durationMs 已知时长；未知传 0
     */
    fun save(
        bytes: ByteArray?,
        contentType: String,
        text: String,
        model: String = "",
        voice: String = "",
        provider: String = "",
        kind: VoiceKind = VoiceKind.AI,
        durationMs: Int = 0,
    ): VoiceEntry? {
        try {
            if (bytes == null && text.isBlank()) return null
            val id = newId()
            var audioFile: File? = null
            var storedType = contentType
            var size = 0L

            if (bytes != null && bytes.isNotEmpty()) {
                val (name, data, type) = materialize(bytes, contentType)
                audioFile = File(dir, "$id$name")
                audioFile.writeBytes(data)
                storedType = type
                size = data.size.toLong()
            }

            val entry = VoiceEntry(
                id = id,
                ts = System.currentTimeMillis(),
                text = text,
                model = model,
                voice = voice,
                provider = provider,
                kind = kind,
                durationMs = durationMs,
                favorite = false,
                contentType = storedType,
                sizeBytes = size,
                audioFile = audioFile,
            )
            writeMeta(entry)
            return entry
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
            return null
        }
    }

    /** PCM → WAV，其它原样；返回 (扩展名, 字节, 实际 contentType)。 */
    private fun materialize(bytes: ByteArray, contentType: String): Triple<String, ByteArray, String> {
        val t = contentType.lowercase()
        val isPcm = t.contains("pcm") || t.contains("l16")
        return when {
            isPcm -> {
                val rate = AudioPlayer.parseRate(contentType) ?: 24000
                val n = bytes.size / 2
                val shorts = Spectrum.le16ToShortsAt(bytes, 0, n)
                Triple(".wav", Wav.fromPcm16(shorts, n, rate), "audio/wav")
            }

            t.contains("mpeg") || t.contains("mp3") -> Triple(".mp3", bytes, contentType)
            t.contains("ogg") -> Triple(".ogg", bytes, contentType)
            t.contains("wave") || t.contains("wav") -> Triple(".wav", bytes, "audio/wav")
            else -> Triple(".bin", bytes, contentType)
        }
    }

    fun updateText(entry: VoiceEntry, text: String) {
        runCatching { writeMeta(entry.copy(text = text)) }
    }

    fun setFavorite(entry: VoiceEntry, favorite: Boolean) {
        runCatching { writeMeta(entry.copy(favorite = favorite)) }
    }

    fun delete(entry: VoiceEntry) {
        runCatching { entry.audioFile?.delete() }
        runCatching { metaFile(entry.id).delete() }
    }

    fun clear(keepFavorites: Boolean = true) {
        list().forEach { if (!keepFavorites || !it.favorite) delete(it) }
    }

    /** 自动清理：超出条数或天数的非收藏项按旧到新删。 */
    fun prune(maxCount: Int, maxDays: Int) {
        val all = list()
        val cutoff = if (maxDays > 0) System.currentTimeMillis() - maxDays * 86_400_000L else 0L
        val removable = all.filter { !it.favorite }.sortedBy { it.ts } // 旧 → 新
        var overCount = if (maxCount > 0) (all.size - maxCount) else 0
        for (e in removable) {
            val tooOld = cutoff > 0 && e.ts < cutoff
            if (tooOld || overCount > 0) {
                delete(e)
                if (overCount > 0) overCount--
            }
        }
    }

    // ---------------- 读 ----------------

    fun list(): List<VoiceEntry> {
        val metas = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<VoiceEntry>(metas.size)
        for (m in metas) {
            runCatching { parseMeta(m) }.getOrNull()?.let { out.add(it) }
        }
        return out.sortedByDescending { it.ts }
    }

    fun readBytes(entry: VoiceEntry): ByteArray? =
        runCatching { entry.audioFile?.takeIf { it.exists() }?.readBytes() }.getOrNull()

    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    // ---------------- sidecar ----------------

    private fun metaFile(id: String) = File(dir, "$id.json")

    private fun writeMeta(entry: VoiceEntry) {
        val o = JSONObject()
            .put("id", entry.id)
            .put("ts", entry.ts)
            .put("text", entry.text)
            .put("model", entry.model)
            .put("voice", entry.voice)
            .put("provider", entry.provider)
            .put("kind", entry.kind.key)
            .put("duration_ms", entry.durationMs)
            .put("favorite", entry.favorite)
            .put("content_type", entry.contentType)
            .put("audio", entry.audioFile?.name ?: "")
            .put("size", entry.sizeBytes)
        metaFile(entry.id).writeText(o.toString(2))
    }

    private fun parseMeta(m: File): VoiceEntry {
        val o = JSONObject(m.readText())
        val id = o.optString("id").ifBlank { m.nameWithoutExtension }
        val audioName = o.optString("audio")
        val audio = if (audioName.isNotBlank()) File(dir, audioName) else null
        return VoiceEntry(
            id = id,
            ts = o.optLong("ts"),
            text = o.optString("text"),
            model = o.optString("model"),
            voice = o.optString("voice"),
            provider = o.optString("provider"),
            kind = VoiceKind.of(o.optString("kind")),
            durationMs = o.optInt("duration_ms"),
            favorite = o.optBoolean("favorite"),
            contentType = o.optString("content_type"),
            sizeBytes = o.optLong("size"),
            audioFile = audio,
        )
    }

    private fun newId(): String {
        val base = String.format(Locale.US, "%d", System.currentTimeMillis())
        if (!metaFile(base).exists()) return base
        var i = 1
        while (metaFile("${base}_$i").exists()) i++
        return "${base}_$i"
    }

    companion object {
        private const val TAG = "VoiceStore"
    }
}
