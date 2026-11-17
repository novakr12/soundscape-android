package io.github.novakr12.soundscape

import org.json.JSONObject

data class Recap(val from: String, val plays: Long, val listeningMs: Long, val topArtist: String?, val rank: Int?, val of: Int?)

data class Today(val plays: Long, val streak: Long, val playedToday: Boolean)

object Extras {
    const val REMINDER_HOUR = 21
    const val RELEASES = "https://github.com/novakr12/soundscape-android/releases/latest"
    const val LATEST = "https://api.github.com/repos/novakr12/soundscape-android/releases/latest"
    const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000

    fun recap(json: String): Pair<Boolean, Recap?>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val show = root.optBoolean("show", false)
        val item = root.optJSONObject("recap") ?: return show to null
        val from = item.optString("from").takeIf { it.isNotEmpty() } ?: return show to null
        val artist = item.optJSONObject("topArtist")?.optString("name")?.takeIf { it.isNotEmpty() }
        val place = item.optJSONObject("place")
        return show to Recap(
            from = from,
            plays = item.optLong("plays", 0),
            listeningMs = item.optLong("listeningMs", 0),
            topArtist = artist,
            rank = place?.optInt("rank", 0)?.takeIf { it > 0 },
            of = place?.optInt("of", 0)?.takeIf { it > 0 },
        )
    }

    fun recapDue(show: Boolean, recap: Recap?, last: String?) = show && recap != null && recap.from != last

    fun recapBody(recap: Recap): String {
        val parts = mutableListOf(if (recap.plays == 1L) "1 play" else "${recap.plays} plays", duration(recap.listeningMs))
        recap.topArtist?.let { parts += "most played: $it" }
        if (recap.rank != null && recap.of != null && recap.of > 1) parts += "#${recap.rank} of ${recap.of} among friends"
        return parts.joinToString(" · ")
    }

    private fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun today(json: String): Today? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (!root.has("streak")) return null
        return Today(root.optLong("plays", 0), root.optLong("streak", 0), root.optBoolean("playedToday", false))
    }

    fun remind(today: Today, hour: Int, date: String, enabled: Boolean, last: String?) =
        enabled && today.streak > 0 && !today.playedToday && hour >= REMINDER_HOUR && last != date

    fun reminderTitle(today: Today) = "Your ${today.streak}-day streak ends at midnight"

    const val REMINDER_BODY = "Play something today to keep it going."

    private fun parts(version: String): List<Long>? {
        val plain = version.trim().trimStart('v', 'V').split('-', '+').first()
        if (plain.isEmpty()) return null
        return plain.split('.').map { it.toLongOrNull() ?: return null }
    }

    fun releaseVersion(json: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optBoolean("draft", false) || root.optBoolean("prerelease", false)) return null
        val tag = root.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
        parts(tag) ?: return null
        return tag.trim().trimStart('v', 'V')
    }

    fun newer(current: String, latest: String): Boolean {
        val have = parts(current) ?: return false
        val offered = parts(latest) ?: return false
        val length = maxOf(have.size, offered.size)
        for (index in 0 until length) {
            val a = have.getOrElse(index) { 0 }
            val b = offered.getOrElse(index) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    fun shouldTell(available: String, enabled: Boolean, told: String?) = enabled && told != available
}
