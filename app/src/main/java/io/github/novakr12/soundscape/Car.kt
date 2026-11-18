package io.github.novakr12.soundscape

import org.json.JSONObject
import java.net.URLEncoder

sealed class CarEntry {
    data class Folder(
        val node: String,
        val title: String,
        val subtitle: String?,
        val cover: String?,
        val playable: Boolean,
    ) : CarEntry()

    data class Song(val track: BridgeTrack) : CarEntry()
}

data class CarPage(val connected: Boolean, val title: String, val items: List<CarEntry>) {
    val songs: List<BridgeTrack> get() = items.mapNotNull { (it as? CarEntry.Song)?.track }
}

object Car {
    const val ROOT = "root"
    const val SHUFFLE = "shuffle"
    const val DOWNLOADS = "downloads"
    private const val SONG = "song|"
    private const val SEARCH = "search:"
    private const val MOST_ITEMS = 500
    private val NODE = Regex("^(root|playlists|shuffle|downloads|albums:(added|played|album)|(album|playlist):[A-Za-z0-9_-]{1,64})$")
    private val COVER = Regex("^/api/navidrome/cover/[A-Za-z0-9_-]{1,64}$")

    fun isNode(text: String) = NODE.matches(text) || isSearch(text)

    fun isSearch(text: String) = text.startsWith(SEARCH) && text.length > SEARCH.length

    fun searchNode(query: String) = SEARCH + query.trim().take(200)

    fun queryOf(node: String) = node.removePrefix(SEARCH)

    fun isPlayableFolder(node: String) = node == SHUFFLE || node == DOWNLOADS || node.startsWith("album:") || node.startsWith("playlist:")

    fun songId(parent: String, index: Int) = "$SONG$parent|$index"

    fun readSongId(id: String): Pair<String, Int>? {
        if (!id.startsWith(SONG)) return null
        val rest = id.removePrefix(SONG)
        val at = rest.lastIndexOf('|')
        if (at <= 0) return null
        val parent = rest.substring(0, at)
        val index = rest.substring(at + 1).toIntOrNull() ?: return null
        return if (index >= 0 && isNode(parent)) parent to index else null
    }

    fun address(server: String, node: String): String = if (isSearch(node)) {
        "$server/api/app/library?search=${encode(queryOf(node))}"
    } else {
        "$server/api/app/library?node=${encode(node)}"
    }

    fun isCoverPath(path: String?) = path != null && COVER.matches(path)

    fun coverUri(authority: String, cover: String?): String? =
        if (isCoverPath(cover)) "content://$authority/cover?path=${encode(cover!!)}" else null

    fun page(json: String?): CarPage? {
        val root = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        val list = root.optJSONArray("items") ?: return null
        val items = (0 until minOf(list.length(), MOST_ITEMS)).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            when (item.optString("kind")) {
                "folder" -> {
                    val node = item.text("node")?.takeIf { isNode(it) } ?: return@mapNotNull null
                    CarEntry.Folder(
                        node = node,
                        title = item.text("title") ?: return@mapNotNull null,
                        subtitle = item.text("subtitle"),
                        cover = item.text("cover"),
                        playable = item.optBoolean("playable", false),
                    )
                }
                "song" -> trackFrom(item.optJSONObject("track"))?.let { CarEntry.Song(it) }
                else -> null
            }
        }
        return CarPage(root.optBoolean("connected", true), root.text("title") ?: "Soundscape", items)
    }

    fun trackJson(track: BridgeTrack): String = JSONObject()
        .put("trackId", track.trackId)
        .put("songId", track.songId ?: JSONObject.NULL)
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album ?: JSONObject.NULL)
        .put("cover", track.cover ?: JSONObject.NULL)
        .put("duration", track.duration ?: JSONObject.NULL)
        .put("level", track.level)
        .toString()

    fun trackFrom(json: String?): BridgeTrack? = trackFrom(runCatching { JSONObject(json ?: return null) }.getOrNull())

    private fun trackFrom(item: JSONObject?): BridgeTrack? {
        item ?: return null
        return BridgeTrack(
            trackId = item.text("trackId") ?: return null,
            songId = item.text("songId"),
            title = item.text("title") ?: return null,
            artist = item.text("artist") ?: return null,
            album = item.text("album"),
            cover = item.text("cover"),
            duration = item.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            level = item.optDouble("level", 1.0).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 1.0,
        )
    }

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
