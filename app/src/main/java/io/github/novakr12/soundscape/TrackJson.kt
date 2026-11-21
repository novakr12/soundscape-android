package io.github.novakr12.soundscape

import org.json.JSONObject
import java.net.URLEncoder

internal fun JSONObject.optionalText(name: String, trim: Boolean = false): String? {
    if (isNull(name)) return null
    val raw = optString(name)
    val value = if (trim) raw.trim() else raw
    return value.takeIf { it.isNotEmpty() }
}

internal fun encodeQueryPart(text: String): String =
    URLEncoder.encode(text, "UTF-8").replace("+", "%20")

object TrackJson {
    const val MAX_ID_LENGTH = 300

    fun encode(track: BridgeTrack): JSONObject = JSONObject()
        .put("trackId", track.trackId)
        .put("songId", track.songId ?: JSONObject.NULL)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album ?: JSONObject.NULL)
        .put("cover", track.cover ?: JSONObject.NULL)
        .put("duration", track.duration ?: JSONObject.NULL)
        .put("level", track.level)

    fun decode(
        item: JSONObject?,
        requireTitle: Boolean = false,
        requireArtist: Boolean = false,
        trim: Boolean = false,
        maxIdLength: Int = Int.MAX_VALUE,
        readLevel: Boolean = true,
    ): BridgeTrack? {
        item ?: return null
        val trackId = item.optionalText("trackId", trim)?.takeIf { it.length <= maxIdLength } ?: return null
        val title = if (requireTitle) {
            item.optionalText("title", trim) ?: return null
        } else {
            item.optString("title").let { if (trim) it.trim() else it }
        }
        val artist = if (requireArtist) {
            item.optionalText("artist", trim) ?: return null
        } else {
            item.optString("artist").let { if (trim) it.trim() else it }
        }
        val level = if (readLevel) {
            item.optDouble("level", 1.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0
        } else {
            1.0
        }
        return BridgeTrack(
            trackId = trackId,
            songId = item.optionalText("songId", trim),
            title = title,
            artist = artist,
            album = item.optionalText("album", trim),
            cover = item.optionalText("cover", trim),
            duration = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            level = level,
        )
    }
}
