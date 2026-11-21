package io.github.novakr12.soundscape

import org.json.JSONObject
import java.net.URI
import kotlin.math.abs

data class DiscordSong(
    val title: String,
    val artist: String,
    val album: String?,
)

data class DiscordPlaying(
    val song: DiscordSong,
    val position: Double?,
    val length: Double?,
    val art: String?,
)

data class DiscordShown(
    val song: DiscordSong,
    val startMs: Long,
    val endMs: Long?,
    val image: String,
    val album: String,
)

data class DiscordLooked(
    val image: String,
    val album: String?,
)

data class DiscordSource(
    val playing: DiscordPlaying,
    val server: String?,
    val token: String?,
)

object Discord {
    const val LOGO = "https://raw.githubusercontent.com/novakr12/soundscape-desktop/main/icons/icon.png"
    const val UNKNOWN_ALBUM = "Unknown album"

    // Discord on Android doesn't fill this in from the app id, so without it the status reads "Listening to Game".
    const val APP_NAME = "Soundscape"

    const val DRIFT_MS = 2_000L
    const val RETRY_AFTER_MS = 30_000L
    const val IDLE_DISCONNECT_MS = 60_000L
    private const val LONGEST_TEXT = 128
    private const val MIN_TEXT = 2
    private const val PAD = '\u2800'
    private const val TYPE_LISTENING = 2
    private const val STATUS_DISPLAY_NAME = 0
    private val NOT_ALBUMS = setOf(
        "Soundscape",
        "30-second preview",
        "Song preview",
        "Unknown album",
    )

    fun validAppId(text: String): Boolean =
        text.length in 17..20 && text.all { it in '0'..'9' } && text.toLongOrNull() != null

    fun realAlbum(album: String?): String? =
        album?.trim()?.takeIf { it.isNotEmpty() && it !in NOT_ALBUMS }

    fun albumLabel(page: String?, looked: String?): String =
        realAlbum(page) ?: realAlbum(looked) ?: UNKNOWN_ALBUM

    fun serverHost(server: String?): String? =
        server?.let { runCatching { URI(it).host }.getOrNull() }?.takeIf { it.isNotEmpty() }

    fun publicArt(art: String?, serverHost: String?): String? {
        if (art.isNullOrBlank()) return null
        val uri = runCatching { URI(art) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        return if (uri.scheme.equals("https", ignoreCase = true) && !host.equals(serverHost, ignoreCase = true)) {
            art
        } else {
            null
        }
    }

    fun timing(nowMs: Long, position: Double?, length: Double?): Pair<Long, Long?> {
        val posMs = ((position?.takeIf { it.isFinite() } ?: 0.0).coerceAtLeast(0.0) * 1000.0).toLong()
        val start = (nowMs - posMs).coerceAtLeast(0L)
        val end = length?.takeIf { it.isFinite() && it > 0.0 }?.let { start + (it * 1000.0).toLong() }
        return start to end
    }

    fun needsUpdate(before: DiscordShown?, after: DiscordShown?): Boolean = when {
        before == null && after == null -> false
        before != null && after != null ->
            before.song != after.song ||
                before.image != after.image ||
                before.album != after.album ||
                endChanged(before.endMs, after.endMs) ||
                abs(before.startMs - after.startMs) > DRIFT_MS
        else -> true
    }

    private fun endChanged(before: Long?, after: Long?): Boolean = when {
        before == null && after == null -> false
        before != null && after != null -> abs(before - after) > DRIFT_MS
        else -> true
    }

    fun short(text: String): String {
        val trimmed = text.trim().take(LONGEST_TEXT)
        return if (trimmed.length >= MIN_TEXT) trimmed else trimmed.padEnd(MIN_TEXT, PAD)
    }

    fun activity(shown: DiscordShown?, pid: Int, nonce: Long): String {
        val payload = shown?.let { item ->
            val timestamps = JSONObject().put("start", item.startMs / 1000L)
            item.endMs?.let { timestamps.put("end", it / 1000L) }
            val assets = JSONObject()
                .put("large_image", item.image)
                .put("large_text", short(item.album))
            JSONObject()
                .put("name", APP_NAME)
                .put("type", TYPE_LISTENING)
                .put("status_display_type", STATUS_DISPLAY_NAME)
                .put("details", short(item.song.title))
                .put("state", short("by ${item.song.artist}"))
                .put("timestamps", timestamps)
                .put("assets", assets)
        }
        return JSONObject()
            .put("cmd", "SET_ACTIVITY")
            .put("args", JSONObject().put("pid", pid).put("activity", payload ?: JSONObject.NULL))
            .put("nonce", nonce.toString())
            .toString()
    }

    fun coverLinkAddress(server: String, artist: String, title: String): String =
        "$server/api/cover-link?artist=${encodeQueryPart(artist)}&title=${encodeQueryPart(title)}"

    fun parseCoverLink(json: String?): Pair<String?, String?> {
        if (json.isNullOrBlank()) return null to null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null to null
        val url = root.optionalText("url", trim = true)?.takeIf { it.startsWith("https://") }
        val album = root.optionalText("album", trim = true)
        return url to album
    }
}
