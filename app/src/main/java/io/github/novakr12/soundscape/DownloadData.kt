package io.github.novakr12.soundscape

import org.json.JSONArray
import org.json.JSONObject

data class DownloadRequestItem(val kind: String, val tracks: List<BridgeTrack>, val trackIds: List<String>)

data class DownloadAsk(val page: String, val requests: List<DownloadRequestItem>)

object DownloadData {
    private const val MOST_AT_ONCE = 20
    private const val MOST_TRACKS = 2000

    fun read(): String = """(() => {
  window.soundscapeAndroidPage ??= String(Math.random()).slice(2);
  const asked = Array.isArray(window.soundscapeDownloadRequests) ? window.soundscapeDownloadRequests.splice(0, $MOST_AT_ONCE) : [];
  return { page: window.soundscapeAndroidPage, asked };
})()"""

    fun parse(json: String?): DownloadAsk? {
        if (json.isNullOrBlank() || json == "null") return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val page = root.optString("page").takeIf { it.isNotEmpty() } ?: return null
        val asked = root.optJSONArray("asked") ?: JSONArray()
        val requests = (0 until minOf(asked.length(), MOST_AT_ONCE)).mapNotNull { index ->
            val item = asked.optJSONObject(index) ?: return@mapNotNull null
            when (item.optString("do")) {
                "download" -> {
                    val list = item.optJSONArray("tracks") ?: return@mapNotNull null
                    val tracks = (0 until minOf(list.length(), MOST_TRACKS)).mapNotNull { at ->
                        list.optJSONObject(at)?.let(::trackFrom)
                    }
                    if (tracks.isEmpty()) null else DownloadRequestItem("download", tracks, emptyList())
                }
                "remove" -> {
                    val list = item.optJSONArray("trackIds") ?: return@mapNotNull null
                    val ids = (0 until minOf(list.length(), MOST_TRACKS)).mapNotNull { at ->
                        list.optString(at).takeIf { it.isNotEmpty() }
                    }
                    if (ids.isEmpty()) null else DownloadRequestItem("remove", emptyList(), ids)
                }
                else -> null
            }
        }
        return DownloadAsk(page, requests)
    }

    fun trackFrom(item: JSONObject): BridgeTrack? {
        val trackId = item.optString("trackId").takeIf { it.isNotEmpty() && it.length <= 300 } ?: return null
        val title = item.optString("title").takeIf { it.isNotEmpty() } ?: return null
        return BridgeTrack(
            trackId = trackId,
            songId = item.optString("songId").takeIf { it.isNotEmpty() && !item.isNull("songId") },
            title = title,
            artist = item.optString("artist"),
            album = item.optString("album").takeIf { it.isNotEmpty() && !item.isNull("album") },
            cover = item.optString("cover").takeIf { it.isNotEmpty() && !item.isNull("cover") },
            duration = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            level = item.optDouble("level", 1.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0,
        )
    }

    fun encode(track: BridgeTrack): ByteArray = JSONObject()
        .put("trackId", track.trackId)
        .put("songId", track.songId ?: JSONObject.NULL)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album ?: JSONObject.NULL)
        .put("cover", track.cover ?: JSONObject.NULL)
        .put("duration", track.duration ?: JSONObject.NULL)
        .put("level", track.level)
        .toString()
        .toByteArray()

    fun decode(data: ByteArray): BridgeTrack? =
        runCatching { JSONObject(String(data)) }.getOrNull()?.let(::trackFrom)

    fun push(version: String, states: Map<String, String>): String {
        val downloads = JSONObject()
        for ((trackId, state) in states) downloads.put(trackId, state)
        val detail = JSONObject().put("version", version).put("downloads", downloads).toString()
        return "(() => { const detail = JSON.parse(${JSONObject.quote(detail)}); window.soundscapeAndroid = detail; window.dispatchEvent(new CustomEvent(\"soundscape-android\", { detail })); })()"
    }

    fun summary(songs: Int, bytes: Long): String? {
        if (songs == 0) return null
        val size = when {
            bytes >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            else -> "${(bytes + 500_000) / 1_000_000} MB"
        }
        return "${if (songs == 1) "1 song" else "$songs songs"} · $size"
    }

    fun groupName(track: BridgeTrack) = track.album ?: "Songs"
}
