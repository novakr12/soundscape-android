package io.github.novakr12.soundscape

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
        val connection = URL("$server/api/radio").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode !in 200..299) return emptyList()
            songs(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
