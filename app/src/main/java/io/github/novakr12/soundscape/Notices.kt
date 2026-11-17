package io.github.novakr12.soundscape

import org.json.JSONObject

data class Notice(val id: Long, val read: Boolean, val title: String, val body: String, val url: String?)

data class Shown(val title: String, val body: String, val url: String?, val outside: Boolean = false)

object Notices {
    private const val AT_MOST = 3

    fun parse(json: String): List<Notice>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val list = root.optJSONArray("notifications") ?: return null
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optLong("id", -1)
            val title = item.optString("title")
            if (id < 0 || title.isEmpty()) return@mapNotNull null
            Notice(
                id = id,
                read = item.optBoolean("read", false),
                title = title,
                body = item.optString("body"),
                url = item.optString("url").takeIf { it.startsWith("/") && !it.startsWith("//") },
            )
        }
    }

    fun fresh(items: List<Notice>, lastSeen: Long?): Pair<List<Notice>, Long> {
        val newest = items.maxOfOrNull { it.id }
        if (lastSeen == null) return emptyList<Notice>() to (newest ?: 0)
        val show = items.filter { it.id > lastSeen && !it.read }.sortedBy { it.id }
        return show to maxOf(lastSeen, newest ?: lastSeen)
    }

    fun summary(items: List<Notice>): List<Shown> {
        val shown = items.map { Shown(it.title, it.body, it.url) }
        if (shown.size <= AT_MOST) return shown
        return shown.take(AT_MOST - 1) +
            Shown("Soundscape", "${shown.size - (AT_MOST - 1)} more notifications", "/notifications")
    }

    fun address(server: String, after: Long?) =
        if (after == null) "$server/api/notifications" else "$server/api/notifications?after=$after"
}
