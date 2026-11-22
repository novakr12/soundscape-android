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
    data class Enqueue(val track: BridgeTrack, val next: Boolean) : RemoteCommand()
    data class Repeat(val mode: String) : RemoteCommand()
    data class Shuffle(val on: Boolean) : RemoteCommand()
    data class Jump(val ahead: Int) : RemoteCommand()
}

data class RemoteSnapshot(
    val track: BridgeTrack?,
    val playing: Boolean,
    val time: Double,
    val duration: Double?,
    val volume: Double,
    val repeat: String? = null,
    val upNext: List<BridgeTrack> = emptyList(),
    val shuffle: Boolean? = null,
)

class EventReader {
    private var event = "message"
    private val data = StringBuilder()
    private var dropped = false

    fun line(text: String): Pair<String, String>? {
        if (text.isEmpty()) {
            val done = if (dropped || data.isEmpty()) null else event to data.toString()
            event = "message"
            dropped = false
            data.setLength(0)
            if (data.capacity() > TRIM_BUFFER_ABOVE) data.trimToSize()
            return done
        }
        if (dropped || text.startsWith(":")) return null
        val at = text.indexOf(':')
        val field = if (at == -1) text else text.substring(0, at)
        val value = if (at == -1) "" else text.substring(at + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                val extra = if (data.isEmpty()) value.length else value.length + 1
                if (data.length + extra > MAX_EVENT_CHARS) {
                    dropped = true
                    data.setLength(0)
                    if (data.capacity() > TRIM_BUFFER_ABOVE) data.trimToSize()
                    return null
                }
                if (data.isNotEmpty()) data.append('\n')
                data.append(value)
            }
        }
        return null
    }

    private companion object {
        const val MAX_EVENT_CHARS = 256 * 1024
        const val TRIM_BUFFER_ABOVE = 4096
    }
}

object Remote {
    const val KIND = "android"
    const val HEARTBEAT_MS = 30_000L
    const val RESTART_AFTER_SECONDS = 3.0
    private const val MOST_TRACKS = 500
    const val MOST_UP_NEXT = 10
    private val REPEAT_MODES = setOf("off", "all", "one")
    private const val LETTERS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")

    fun isDeviceId(text: String?) = text != null && DEVICE_ID.matches(text)

    fun newDeviceId(random: Random = Random.Default) = (1..20).map { LETTERS[random.nextInt(LETTERS.length)] }.joinToString("")

    fun deviceName(model: String): String {
        val plain = model.trim().take(40)
        return if (plain.isEmpty()) "Soundscape on Android" else "Soundscape on $plain"
    }

    fun pageScript(id: String, name: String) =
        "window.soundscapeDeviceId = ${JSONObject.quote(id)}; window.soundscapeDeviceName = ${JSONObject.quote(name)}; window.soundscapeBridgeRemote = true;"

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
            .put("repeat", snapshot.repeat?.takeIf { it in REPEAT_MODES } ?: JSONObject.NULL)
            .put("shuffle", snapshot.shuffle ?: JSONObject.NULL)
            .put(
                "upNext",
                JSONArray().apply {
                    snapshot.upNext.take(MOST_UP_NEXT).forEach { next ->
                        put(JSONObject().put("trackId", next.trackId).put("title", next.title).put("artist", next.artist))
                    }
                },
            )
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
            "repeat" -> item.optString("mode").takeIf { it in REPEAT_MODES }?.let { RemoteCommand.Repeat(it) }
            "shuffle" -> when (item.optString("on")) {
                "true" -> RemoteCommand.Shuffle(true)
                "false" -> RemoteCommand.Shuffle(false)
                else -> null
            }
            "jump" -> item.optInt("ahead", 0).takeIf { it in 1..MOST_UP_NEXT }?.let { RemoteCommand.Jump(it) }
            "enqueue" -> item.optJSONObject("track")?.let(::track)?.let {
                RemoteCommand.Enqueue(it, item.optBoolean("next", false))
            }
            else -> null
        }
    }

    private fun take(item: JSONObject): RemoteCommand? {
        val list = item.optJSONArray("tracks") ?: return null
        val tracks = (0 until minOf(list.length(), MOST_TRACKS)).mapNotNull { index ->
            list.optJSONObject(index)?.let(::track)
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

    private fun track(item: JSONObject): BridgeTrack? =
        TrackJson.decode(item, requireTitle = true, requireArtist = true, trim = true, readLevel = false)

    fun isForMe(json: String, id: String) = runCatching { JSONObject(json).optString("to") == id }.getOrDefault(false)
}
