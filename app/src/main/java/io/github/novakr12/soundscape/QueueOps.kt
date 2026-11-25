package io.github.novakr12.soundscape

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RemovedQueue(
    val tracks: List<BridgeTrack>,
    val unshuffled: List<BridgeTrack>?,
    val targetIndex: Int,
)

data class MovedQueue(
    val tracks: List<BridgeTrack>,
    val fromIndex: Int,
    val toIndex: Int,
)

data class SavedQueue(
    val server: String,
    val tracks: List<BridgeTrack>,
    val position: Int,
    val time: Double,
    val repeat: String,
    val shuffle: Boolean,
    val unshuffled: List<BridgeTrack>? = null,
)

object QueueOps {
    const val QUEUE_FILE = "player-queue.json"
    private val REPEAT_MODES = setOf("off", "all", "one")

    fun removeUpcoming(
        tracks: List<BridgeTrack>,
        current: Int,
        ahead: Int,
        unshuffled: List<BridgeTrack>?,
    ): RemovedQueue? {
        if (ahead < 1 || current < 0 || current + ahead >= tracks.size) return null
        val target = current + ahead
        val next = tracks.toMutableList()
        val removed = next.removeAt(target)
        val nextUnshuffled = unshuffled?.toMutableList()?.apply {
            val found = indexOfFirst { it.trackId == removed.trackId && it.songId == removed.songId }
            if (found >= 0) removeAt(found)
        }
        return RemovedQueue(next, nextUnshuffled, target)
    }

    fun moveUpcoming(
        tracks: List<BridgeTrack>,
        current: Int,
        from: Int,
        to: Int,
    ): MovedQueue? {
        if (from < 1 || to < 1 || from == to || current < 0) return null
        val src = current + from
        val dst = current + to
        if (src >= tracks.size || dst >= tracks.size) return null
        val next = tracks.toMutableList()
        next.add(dst, next.removeAt(src))
        return MovedQueue(next, src, dst)
    }

    fun window(
        tracks: List<BridgeTrack>,
        position: Int,
        maxTracks: Int = Remote.MOST_TRACKS,
    ): Pair<List<BridgeTrack>, Int>? {
        if (tracks.isEmpty() || position !in tracks.indices || maxTracks <= 0) return null
        val from = (position - maxTracks / 2)
            .coerceAtLeast(0)
            .coerceAtMost((tracks.size - maxTracks).coerceAtLeast(0))
        val part = tracks.subList(from, minOf(tracks.size, from + maxTracks)).toList()
        return part to (position - from)
    }

    fun encodeSavedQueue(saved: SavedQueue): String = JSONObject()
        .put("server", saved.server)
        .put("tracks", JSONArray().apply { saved.tracks.forEach { put(TrackJson.encode(it)) } })
        .put("position", saved.position)
        .put("time", saved.time.takeIf { it.isFinite() && it > 0 } ?: 0.0)
        .put("repeat", saved.repeat.takeIf { it in REPEAT_MODES } ?: "off")
        .put("shuffle", saved.shuffle)
        .apply {
            saved.unshuffled?.let { list ->
                put("unshuffled", JSONArray().apply { list.forEach { put(TrackJson.encode(it)) } })
            }
        }
        .toString()

    fun decodeSavedQueue(json: String?, expectedServer: String): SavedQueue? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.optString("server") != expectedServer) return null
        val list = root.optJSONArray("tracks") ?: return null
        val rawTracks = (0 until list.length()).mapNotNull {
            TrackJson.decode(list.optJSONObject(it), requireTitle = false, requireArtist = false, trim = false)
        }
        if (rawTracks.size != list.length()) return null
        val rawPos = root.optInt("position", -1)
        val (tracks, position) = window(rawTracks, rawPos) ?: return null
        val repeat = root.optString("repeat").takeIf { it in REPEAT_MODES } ?: "off"
        val time = root.optDouble("time", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0
        val shuffle = root.optBoolean("shuffle", false)
        val unshuffled = if (shuffle && root.has("unshuffled") && !root.isNull("unshuffled")) {
            val arr = root.optJSONArray("unshuffled")
            arr?.let {
                (0 until it.length()).mapNotNull { index ->
                    TrackJson.decode(it.optJSONObject(index), requireTitle = false, requireArtist = false, trim = false)
                }
            }
        } else {
            null
        }
        return SavedQueue(expectedServer, tracks, position, time, repeat, shuffle, unshuffled)
    }

    fun saveFile(dir: File, saved: SavedQueue) {
        if (!dir.exists() && !dir.mkdirs()) return
        val target = File(dir, QUEUE_FILE)
        val temp = File(dir, "$QUEUE_FILE.tmp")
        runCatching {
            temp.writeText(encodeSavedQueue(saved), Charsets.UTF_8)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }.onFailure {
            runCatching { temp.delete() }
        }
    }

    fun loadFile(dir: File, expectedServer: String): SavedQueue? {
        val file = File(dir, QUEUE_FILE)
        val text = runCatching { if (file.isFile) file.readText(Charsets.UTF_8) else null }.getOrNull() ?: return null
        return decodeSavedQueue(text, expectedServer)
    }

    fun clearFile(dir: File) {
        runCatching { File(dir, QUEUE_FILE).delete() }
        runCatching { File(dir, "$QUEUE_FILE.tmp").delete() }
    }

    fun playlistRequestBody(name: String, tracks: List<BridgeTrack>, maxTracks: Int = Remote.MOST_TRACKS): String =
        JSONObject()
            .put("name", name)
            .put(
                "tracks",
                JSONArray().apply {
                    tracks.take(maxTracks).forEach { track ->
                        val item = JSONObject()
                            .put("trackId", track.trackId)
                            .put("title", track.title)
                            .put("artist", track.artist)
                        track.songId?.let { item.put("songId", it) }
                        put(item)
                    }
                },
            )
            .toString()

    fun playlistErrorDetail(id: String, message: String): String =
        JSONObject()
            .put("id", id)
            .put("ok", false)
            .put("error", message)
            .toString()

    fun playlistResultDetail(id: String, name: String, answer: HttpAnswer?): String {
        if (answer == null) return playlistErrorDetail(id, "Could not reach Soundscape.")
        val parsed = runCatching { answer.body?.let(::JSONObject) }.getOrNull()
        if (answer.status in 200..299) {
            return JSONObject()
                .put("id", id)
                .put("ok", true)
                .put("name", parsed?.optionalText("name") ?: name)
                .put("saved", parsed?.optInt("saved", 0) ?: 0)
                .put("total", parsed?.optInt("total", 0) ?: 0)
                .put("missing", parsed?.optInt("missing", 0) ?: 0)
                .toString()
        }
        val message = parsed?.optionalText("error") ?: "Could not save playlist."
        return playlistErrorDetail(id, message)
    }
}
