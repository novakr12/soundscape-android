package io.github.novakr12.soundscape

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

sealed class RemoteCommand {
    data object Play : RemoteCommand()
    data object Pause : RemoteCommand()
    data object Toggle : RemoteCommand()
    data object Next : RemoteCommand()
    data object Previous : RemoteCommand()
    data class Seek(val seconds: Double) : RemoteCommand()
    data class Volume(val volume: Double) : RemoteCommand()
    data class Take(val tracks: List<BridgeTrack>, val position: Int, val time: Double, val playing: Boolean) :
        RemoteCommand()
    data class Send(val to: String) : RemoteCommand()
}

data class RemoteSnapshot(
    val track: BridgeTrack?,
    val playing: Boolean,
    val time: Double,
    val duration: Double?,
    val volume: Double,
)

class EventReader {
    private var event = "message"
    private val data = StringBuilder()

    fun line(text: String): Pair<String, String>? {
        if (text.isEmpty()) {
            val done = if (data.isEmpty()) null else event to data.toString()
            event = "message"
            data.setLength(0)
            return done
        }
        if (text.startsWith(":")) return null
        val at = text.indexOf(':')
        val field = if (at == -1) text else text.substring(0, at)
        val value = if (at == -1) "" else text.substring(at + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }
}

object Remote {
    const val KIND = "android"
    const val HEARTBEAT_MS = 30_000L
    const val RESTART_AFTER_SECONDS = 3.0
    private const val MOST_TRACKS = 500
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun isDeviceId(text: String?) = text != null && DEVICE_ID.matches(text)

    fun newDeviceId(random: Random = Random.Default) = (1..20).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")

    fun deviceName(model: String): String {
        val plain = model.trim().take(40)
        return if (plain.isEmpty()) "Soundscape on Android" else "Soundscape on $plain"
    }

    fun pageScript(id: String, name: String) =
        "window.soundscapeDeviceId = ${JSONObject.quote(id)}; window.soundscapeDeviceName = ${JSONObject.quote(name)};"

    fun holdScript(held: Boolean) = if (held) {
        "(() => { window.soundscapeDeviceHeld = true; })()"
    } else {
        "(() => { window.soundscapeDeviceHeld = false; window.dispatchEvent(new Event(\"soundscape-device-report\")); })()"
    }

    fun report(id: String, name: String, snapshot: RemoteSnapshot): String {
        val state = JSONObject()
            .put("playing", snapshot.track != null && snapshot.playing)
            .put("time", snapshot.time.takeIf { it.isFinite() && it > 0 } ?: 0.0)
            .put("duration", snapshot.duration?.takeIf { it.isFinite() && it > 0 } ?: JSONObject.NULL)
            .put("volume", snapshot.volume.coerceIn(0.0, 1.0))
            .put("blocked", false)
            .put("track", snapshot.track?.let { track ->
                JSONObject()
                    .put("trackId", track.trackId)
                    .put("title", track.title)
                    .put("artist", track.artist)
                    .put("album", track.album ?: JSONObject.NULL)
                    .put("cover", track.cover ?: JSONObject.NULL)
                    .put("songId", track.songId ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
        return JSONObject().put("id", id).put("name", name).put("kind", KIND).put("state", state).toString()
    }

    fun handed(tracks: List<BridgeTrack>): JSONArray = JSONArray().apply {
        tracks.take(MOST_TRACKS).forEach { track ->
            val item = JSONObject().put("trackId", track.trackId).put("title", track.title).put("artist", track.artist)
            track.album?.let { item.put("album", it) }
            track.cover?.let { item.put("cover", it) }
            track.songId?.let { item.put("songId", it) }
            track.duration?.let { item.put("duration", it) }
            put(item)
        }
    }

    fun send(to: String, tracks: List<BridgeTrack>, position: Int, time: Double): String? {
        if (tracks.isEmpty() || position !in tracks.indices || !isDeviceId(to)) return null
        val from = (position - MOST_TRACKS / 2).coerceAtLeast(0)
        val part = tracks.subList(from, minOf(tracks.size, from + MOST_TRACKS))
        val command = JSONObject()
            .put("do", "take")
            .put("tracks", handed(part))
            .put("position", position - from)
            .put("time", time.takeIf { it.isFinite() && it > 0 } ?: 0.0)
            .put("playing", true)
        return JSONObject().put("to", to).put("command", command).toString()
    }

    fun commands(json: String?): List<RemoteCommand> {
        val list = runCatching { JSONObject(json ?: return emptyList()) }.getOrNull()?.optJSONArray("commands")
            ?: return emptyList()
        return (0 until list.length()).mapNotNull { index -> command(list.optJSONObject(index)) }
    }

    fun command(item: JSONObject?): RemoteCommand? {
        item ?: return null
        return when (item.optString("do")) {
            "play" -> RemoteCommand.Play
            "pause" -> RemoteCommand.Pause
            "toggle" -> RemoteCommand.Toggle
            "next" -> RemoteCommand.Next
            "previous" -> RemoteCommand.Previous
            "seek" -> item.optDouble("time").takeIf { it.isFinite() && it >= 0 }?.let { RemoteCommand.Seek(it) }
            "volume" -> item.optDouble("volume").takeIf { it.isFinite() && it in 0.0..1.0 }?.let { RemoteCommand.Volume(it) }
            "send" -> item.optString("to").takeIf { isDeviceId(it) }?.let { RemoteCommand.Send(it) }
            "take" -> take(item)
            else -> null
        }
    }

    private fun take(item: JSONObject): RemoteCommand? {
        val list = item.optJSONArray("tracks") ?: return null
        val tracks = (0 until minOf(list.length(), MOST_TRACKS)).mapNotNull { index ->
            val track = list.optJSONObject(index) ?: return@mapNotNull null
            val trackId = track.text("trackId") ?: return@mapNotNull null
            BridgeTrack(
                trackId = trackId,
                songId = track.text("songId"),
                title = track.text("title") ?: return@mapNotNull null,
                artist = track.text("artist") ?: return@mapNotNull null,
                album = track.text("album"),
                cover = track.text("cover"),
                duration = track.optDouble("duration").takeIf { it.isFinite() && it > 0 },
                level = 1.0,
            )
        }
        val position = item.optInt("position", -1)
        if (tracks.isEmpty() || position !in tracks.indices) return null
        return RemoteCommand.Take(
            tracks,
            position,
            item.optDouble("time", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
            item.optBoolean("playing", true),
        )
    }

    fun isForMe(json: String, id: String) = runCatching { JSONObject(json).optString("to") == id }.getOrDefault(false)

    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }
}
