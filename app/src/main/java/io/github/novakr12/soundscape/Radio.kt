package io.github.novakr12.soundscape

import org.json.JSONArray
import org.json.JSONObject

object Radio {
    const val REFILL_BELOW = 3
    const val REMEMBERS = 300

    fun needsMore(autoplay: Boolean, repeatOff: Boolean, left: Int): Boolean =
        autoplay && repeatOff && left < REFILL_BELOW

    fun body(seed: BridgeTrack, exclude: List<String>, skipTracks: List<String>): String =
        JSONObject()
            .put("trackId", seed.trackId)
            .put("artist", seed.artist)
            .put("title", seed.title)
            .put("continuing", true)
            .put("exclude", JSONArray(exclude))
            .put("skipTracks", JSONArray(skipTracks.takeLast(REMEMBERS)))
            .toString()

    fun songs(json: String): List<BridgeTrack> {
        val list = runCatching { JSONObject(json).optJSONArray("songs") }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { at -> list.optJSONObject(at)?.let(DownloadData::trackFrom) }
    }

    fun fetch(server: String, token: String, body: String): List<BridgeTrack> {
        val answer = Net.postJson(
            address = "$server/api/radio",
            body = body,
            token = token,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 20_000,
        )
        if (answer.status !in 200..299 || answer.body == null) return emptyList()
        return songs(answer.body)
    }
}
