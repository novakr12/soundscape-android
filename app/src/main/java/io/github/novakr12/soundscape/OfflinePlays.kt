package io.github.novakr12.soundscape

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class OfflinePlay(val artist: String, val title: String, val album: String?, val playedAt: Long)

object OfflinePlays {
    private const val LONGEST = 5000
    private const val BATCH_SIZE = 1000
    private const val MAX_BATCHES = (LONGEST / BATCH_SIZE) + 1
    private const val FILE = "offline-plays.json"
    private val lock = Any()
    private val sending = AtomicBoolean(false)

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
            val artist = item.optionalText("artist") ?: return@mapNotNull null
            val title = item.optionalText("title") ?: return@mapNotNull null
            val playedAt = item.optLong("playedAt", 0).takeIf { it > 0 } ?: return@mapNotNull null
            OfflinePlay(artist, title, item.optionalText("album"), playedAt)
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

    fun withoutSent(current: List<OfflinePlay>, sent: List<OfflinePlay>): List<OfflinePlay> {
        if (sent.isEmpty() || current.isEmpty()) return current
        if (sent.size <= current.size && current.subList(0, sent.size) == sent) {
            return current.drop(sent.size)
        }
        val maxOverlap = minOf(current.size, sent.size)
        for (overlap in maxOverlap downTo 1) {
            if (current.subList(0, overlap) == sent.subList(sent.size - overlap, sent.size)) {
                return current.drop(overlap)
            }
        }
        return current
    }

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun writeLocked(context: Context, plays: List<OfflinePlay>) {
        val target = file(context)
        val temp = File(target.parentFile, "$FILE.tmp")
        if (plays.isEmpty()) {
            temp.delete()
            target.delete()
            return
        }
        val encoded = encode(plays)
        temp.writeText(encoded)
        if (!temp.renameTo(target)) {
            target.writeText(encoded)
            temp.delete()
        }
    }

    fun waiting(context: Context): List<OfflinePlay> = synchronized(lock) {
        runCatching {
            file(context).takeIf { it.exists() }?.readText()?.let(::decode)
        }.getOrNull() ?: emptyList()
    }

    fun add(context: Context, track: BridgeTrack, playedAt: Long) = synchronized(lock) {
        val plays = waiting(context) + OfflinePlay(track.artist, track.title, track.album, playedAt)
        writeLocked(context, plays)
    }

    fun send(context: Context) {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_OFFLINE_PLAYS_SEND)
            return
        }
        val settings = Settings(app)
        val server = settings.server ?: return
        val token = settings.token ?: return
        if (!sending.compareAndSet(false, true)) return
        try {
            repeat(MAX_BATCHES) {
                val batch = waiting(app).take(BATCH_SIZE)
                if (batch.isEmpty()) return
                val sent = runCatching {
                    Net.postJson(
                        address = "$server/1/submit-listens",
                        body = body(batch),
                        token = token,
                        connectTimeoutMs = 15_000,
                        readTimeoutMs = 30_000,
                        readResponse = false,
                    ).status in 200..299
                }.getOrDefault(false)
                if (!sent) return
                synchronized(lock) {
                    writeLocked(app, withoutSent(waiting(app), batch))
                }
            }
        } finally {
            sending.set(false)
        }
    }
}
