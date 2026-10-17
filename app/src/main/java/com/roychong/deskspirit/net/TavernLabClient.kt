package com.roychong.deskspirit.net

import org.json.JSONObject
import java.io.IOException

/**
 * TavernLab 的角色接口。
 *
 * TavernLab 的不变式是 **session ≡ 角色名**，而「当前角色」是**服务端全局**的
 * (`current_char`)，网页和 App 共享同一份。所以这里"切角色" = 改服务端设置，
 * 不是 App 本地状态。（App 发消息时也不带 session，由服务端按 current_char 解析。）
 *
 * 复用「LLM 对话」那个 profile 的 base/Key：同一个 app token 就能过 `/api/` 的鉴权。
 */
object TavernLabClient {

    data class Char(
        val name: String,
        val description: String,
        val avatarUrl: String,
        val avatarPx: Int,
        val hasCard: Boolean,
    )

    /** `GET /api/characters` */
    fun characters(base: String, key: String): List<Char> {
        val root = rootOf(base)
        val body = get("$root/api/characters", key)
        val arr = runCatching { JSONObject(body).optJSONArray("characters") }.getOrNull()
            ?: throw IOException("角色列表格式不对：${body.take(160)}")
        val out = ArrayList<Char>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isBlank()) continue
            out.add(
                Char(
                    name = name,
                    description = o.optString("description"),
                    avatarUrl = o.optString("avatar_url"),
                    avatarPx = o.optInt("avatar_px", 88),
                    hasCard = o.optBoolean("has_card"),
                )
            )
        }
        return out
    }

    /** `GET /api/settings` → `current_char` */
    fun currentChar(base: String, key: String): String {
        val root = rootOf(base)
        val body = get("$root/api/settings", key)
        return runCatching { JSONObject(body).optString("current_char") }.getOrDefault("")
    }

    /** `PUT /api/settings {"current_char": …}`：切换当前角色（服务端全局）。 */
    fun setCurrentChar(base: String, key: String, name: String) {
        val root = rootOf(base)
        val payload = JSONObject().put("current_char", name).toString()
        Http.retryOnce {
            val conn = Http.open("$root/api/settings", key, "PUT", readTimeoutMs = 20_000)
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            try {
                conn.outputStream.use { it.write(payload.toByteArray()) }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = Http.readText(conn.errorStream)
                    throw IOException("切换角色 HTTP $code：${err.take(160)}")
                }
            } finally {
                Http.close(conn)
            }
        }
    }

    /**
     * 取角色头像字节。`/chars/…` 同样走鉴权（`<img>` 传不了 header），
     * 所以自己带 header 拉字节再交给 UI 解码。
     */
    fun avatarBytes(base: String, key: String, avatarUrl: String): ByteArray? {
        if (avatarUrl.isBlank()) return null
        val root = rootOf(base)
        val path = if (avatarUrl.startsWith("/")) avatarUrl else "/$avatarUrl"
        return runCatching {
            Http.retryOnce {
                val conn = Http.open("$root$path", key, readTimeoutMs = 20_000)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IOException("头像 HTTP $code")
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    Http.close(conn)
                }
            }
        }.getOrNull()
    }

    private fun rootOf(base: String): String {
        val r = Http.apiRoot(base)
        if (r.isEmpty()) throw IOException("TavernLab Base URL 为空")
        return r
    }

    private fun get(url: String, key: String): String = Http.retryOnce {
        val conn = Http.open(url, key, readTimeoutMs = 20_000)
        try {
            val code = conn.responseCode
            val body = Http.readText(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw IOException("HTTP $code：${body.take(160)}")
            body
        } finally {
            Http.close(conn)
        }
    }
}
