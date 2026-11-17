package io.github.novakr12.soundscape

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class OfflinePlay(val artist: String, val title: String, val album: String?, val playedAt: Long)

object OfflinePlays {
    private const val LONGEST = 5000
    private const val FILE = "offline-plays.json"
    private val lock = Any()

    fun body(plays: List<OfflinePlay>): String {
        val payload = JSONArray()
        for (play in plays) {
            val metadata = JSONObject().put("artist_name", play.artist).put("track_name", play.title)
            play.album?.let { metadata.put("release_name", it) }
            payload.put(JSONObject().put("listened_at", play.playedAt).put("track_metadata", metadata))
        }
        return JSONObject().put("listen_type", "import").put("payload", payload).toString()
    }

    fun decode(text: String): List<OfflinePlay> {
        val list = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { index ->
            val item = list.optJSONObject(index) ?: return@mapNotNull null
            val artist = item.optString("artist").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = item.optString("title").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val playedAt = item.optLong("playedAt", 0).takeIf { it > 0 } ?: return@mapNotNull null
            OfflinePlay(artist, title, item.optString("album").takeIf { it.isNotEmpty() && !item.isNull("album") }, playedAt)
        }
    }

    fun encode(plays: List<OfflinePlay>): String {
        val list = JSONArray()
        for (play in plays.takeLast(LONGEST)) {
            list.put(
                JSONObject()
                    .put("artist", play.artist)
                    .put("title", play.title)
                    .put("album", play.album ?: JSONObject.NULL)
                    .put("playedAt", play.playedAt),
            )
        }
        return list.toString()
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun waiting(context: Context): List<OfflinePlay> = synchronized(lock) {
        file(context).takeIf { it.exists() }?.readText()?.let(::decode) ?: emptyList()
    }

    fun add(context: Context, track: BridgeTrack, playedAt: Long) = synchronized(lock) {
        val plays = waiting(context) + OfflinePlay(track.artist, track.title, track.album, playedAt)
        file(context).writeText(encode(plays))
    }

    fun send(context: Context) {
        val settings = Settings(context)
        val server = settings.server ?: return
        val token = settings.token ?: return
        val plays = waiting(context)
        if (plays.isEmpty()) return
        val sent = runCatching {
            val connection = URL("$server/1/submit-listens").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Token $token")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body(plays.take(1000)).toByteArray()) }
            val status = connection.responseCode
            connection.disconnect()
            status in 200..299
        }.getOrDefault(false)
        if (!sent) return
        synchronized(lock) {
            val now = waiting(context)
            file(context).writeText(encode(now.drop(minOf(plays.size, 1000))))
        }
    }
}
