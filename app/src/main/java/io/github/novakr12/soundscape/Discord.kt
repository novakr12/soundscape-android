package io.github.novakr12.soundscape

import org.json.JSONObject
import java.net.URI
import java.util.Locale
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

data class CachedLookup(
    val looked: DiscordLooked,
    val retryAt: Long?,
)

data class DiscordSource(
    val playing: DiscordPlaying,
    val server: String?,
    val token: String?,
)

object Discord {
    const val LOGO = "https://raw.githubusercontent.com/novakr12/soundscape-desktop/main/icons/icon.png"
    const val UNKNOWN_ALBUM = "Unknown album"

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

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0..3) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return octets
    }

    private fun isPrivateIpv4(a: Int, b: Int, c: Int): Boolean =
        a == 0 ||
            a == 10 ||
            (a == 100 && b in 64..127) ||
            a == 127 ||
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 0 && c == 2) ||
            (a == 192 && b == 168) ||
            (a == 198 && b == 51 && c == 100) ||
            (a == 203 && b == 0 && c == 113) ||
            a >= 224

    private fun parseIpv6Words(host: String): IntArray? {
        if (':' !in host) return null
        var normalized = host
        val lastColon = normalized.lastIndexOf(':')
        val tail = normalized.substring(lastColon + 1)
        if ('.' in tail) {
            val octets = parseIpv4(tail) ?: return null
            val hi = ((octets[0] shl 8) or octets[1]).toString(16)
            val lo = ((octets[2] shl 8) or octets[3]).toString(16)
            normalized = normalized.substring(0, lastColon + 1) + "$hi:$lo"
        }
        val halves = normalized.split("::")
        if (halves.size > 2) return null
        fun parsePart(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val chunks = part.split(':')
            val out = ArrayList<Int>(chunks.size)
            for (chunk in chunks) {
                if (chunk.isEmpty() || chunk.length > 4) return null
                val word = chunk.toIntOrNull(16) ?: return null
                out.add(word)
            }
            return out
        }
        val head = parsePart(halves[0]) ?: return null
        val tailWords = if (halves.size == 2) parsePart(halves[1]) ?: return null else emptyList()
        val missing = if (halves.size == 2) 8 - (head.size + tailWords.size) else 0
        if (missing < 0 || (halves.size == 1 && head.size != 8)) return null
        return IntArray(8).apply {
            for (i in head.indices) this[i] = head[i]
            val tailStart = head.size + missing
            for (i in tailWords.indices) this[tailStart + i] = tailWords[i]
        }
    }

    private fun isPrivateIpv6(words: IntArray): Boolean {
        if ((0..6).all { words[it] == 0 } && words[7] <= 1) return true
        if ((words[0] and 0xfe00) == 0xfc00) return true
        if ((words[0] and 0xffc0) == 0xfe80) return true
        if ((words[0] and 0xff00) == 0xff00) return true
        if ((0..4).all { words[it] == 0 } && words[5] == 0xffff) {
            return isPrivateIpv4(words[6] ushr 8, words[6] and 0xff, words[7] ushr 8)
        }
        return false
    }

    fun isPrivateHost(host: String): Boolean {
        val clean = host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.')
            .lowercase(Locale.ROOT)
        if (clean.isEmpty() ||
            clean == "localhost" ||
            clean.endsWith(".localhost") ||
            clean.endsWith(".ts.net") ||
            clean.endsWith(".local") ||
            clean.endsWith(".lan") ||
            clean.endsWith(".home") ||
            clean.endsWith(".home.arpa") ||
            clean.endsWith(".internal") ||
            clean.endsWith(".localdomain") ||
            ('.' !in clean && ':' !in clean)
        ) {
            return true
        }
        parseIpv4(clean)?.let { octets -> return isPrivateIpv4(octets[0], octets[1], octets[2]) }
        parseIpv6Words(clean)?.let { words -> return isPrivateIpv6(words) }
        return false
    }

    fun publicArt(art: String?, serverHost: String?): String? {
        if (art.isNullOrBlank()) return null
        val uri = runCatching { URI(art) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        return if (
            uri.scheme.equals("https", ignoreCase = true) &&
            !host.equals(serverHost, ignoreCase = true) &&
            !isPrivateHost(host)
        ) {
            art
        } else {
            null
        }
    }

    fun usableCoverLink(url: String?): String? = publicArt(url, null)

    fun cacheLookup(
        art: String?,
        pageAlbum: String?,
        remote: Pair<String?, String?>?,
        previousAlbum: String?,
        lookupSucceeded: Boolean,
        nowMs: Long,
        retryAfterMs: Long,
    ): CachedLookup {
        val resolvedImage = art ?: remote?.first
        val resolvedAlbum = pageAlbum ?: remote?.second ?: previousAlbum
        val looked = DiscordLooked(
            image = resolvedImage ?: LOGO,
            album = resolvedAlbum,
        )
        val complete = resolvedImage != null && (resolvedAlbum != null || lookupSucceeded)
        return CachedLookup(
            looked = looked,
            retryAt = if (complete) null else nowMs + retryAfterMs,
        )
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
        val url = usableCoverLink(root.optionalText("url", trim = true))
        val album = root.optionalText("album", trim = true)
        return url to album
    }
}
