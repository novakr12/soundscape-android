package io.github.novakr12.soundscape

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import org.json.JSONArray
import org.json.JSONObject

object PlaybackCodecs {
    fun encodePageState(state: PageState): String {
        val obj = JSONObject()
            .put("tracks", JSONArray().apply { state.tracks.forEach { put(TrackJson.encode(it)) } })
            .put("position", state.position)
            .put("time", state.time)
            .put("playing", state.playing)
            .put("volume", state.volume)
            .put("repeat", state.repeat)
        state.duration?.takeIf { it.isFinite() && it > 0 }?.let { obj.put("duration", it) }
        state.remote?.takeIf { it.isNotEmpty() }?.let { obj.put("remote", JSONObject().put("name", it)) }
        state.handing?.let { obj.put("handing", it) }
        return obj.toString()
    }

    fun encodeHandBack(back: HandBack): String {
        val obj = JSONObject()
            .put("position", back.position)
            .put("time", back.time)
            .put("playing", back.playing)
        back.tracks?.let { list ->
            obj.put("tracks", JSONArray().apply { list.forEach { put(TrackJson.encode(it)) } })
        }
        back.at?.let { obj.put("at", it) }
        return obj.toString()
    }

    fun decodeHandBack(json: String?): HandBack? {
        if (json.isNullOrBlank()) return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val position = root.optInt("position", -1)
        if (position < 0) return null
        val time = root.optDouble("time", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0
        val playing = root.optBoolean("playing", false)
        val tracks = if (root.has("tracks") && !root.isNull("tracks")) {
            val array = root.optJSONArray("tracks") ?: return null
            val decoded = decodeTracksArray(array)
            if (decoded.size != array.length()) return null
            decoded
        } else {
            null
        }
        val at = if (root.has("at") && !root.isNull("at")) root.optLong("at") else null
        return HandBack(position, time, playing, tracks, at)
    }

    fun encodeTracks(tracks: List<BridgeTrack>): String = JSONArray().apply {
        tracks.forEach { put(TrackJson.encode(it)) }
    }.toString()

    fun decodeTracks(json: String?): List<BridgeTrack> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return decodeTracksArray(array)
    }

    private fun decodeTracksArray(array: JSONArray): List<BridgeTrack> =
        (0 until array.length()).mapNotNull { index ->
            TrackJson.decode(array.optJSONObject(index), requireTitle = false, requireArtist = false, trim = false)
        }

    fun encodeStoredList(items: List<Stored>): String = JSONArray().apply {
        for (item in items) {
            put(
                JSONObject()
                    .put("track", TrackJson.encode(item.track))
                    .put("state", item.state)
                    .put("bytes", item.bytes)
                    .put("percent", if (item.percent.isFinite()) item.percent.toDouble() else -1.0),
            )
        }
    }.toString()

    fun decodeStoredList(json: String?): List<Stored> {
        if (json.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val track = TrackJson.decode(obj.optJSONObject("track"), requireTitle = false, requireArtist = false)
                ?: return@mapNotNull null
            Stored(
                track = track,
                state = obj.optInt("state", 0),
                bytes = obj.optLong("bytes", 0L),
                percent = obj.optDouble("percent", -1.0).toFloat(),
            )
        }
    }

    fun encodeStringMap(map: Map<String, String>): String = JSONObject().apply {
        for ((key, value) in map) put(key, value)
    }.toString()

    fun decodeStringMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key)
        }
        return out
    }
}

@OptIn(UnstableApi::class)
object PlaybackBridge {
    const val HINT_PAGE_CLIENT = "soundscape.page_client"

    // Commands sent from UI process to PlaybackService
    const val ACTION_SHOW_PAGE = "soundscape.cmd.show_page"
    const val ACTION_TAKE_OVER = "soundscape.cmd.take_over"
    const val ACTION_OBEY_PAGE = "soundscape.cmd.obey_page"
    const val ACTION_TELL_PAGE = "soundscape.cmd.tell_page"
    const val ACTION_PLAY_DOWNLOADS = "soundscape.cmd.play_downloads"
    const val ACTION_HAND_BACK_STATE = "soundscape.cmd.hand_back_state"
    const val ACTION_HAND_BACK_START = "soundscape.cmd.hand_back_start"
    const val ACTION_HAND_BACK_ABANDON = "soundscape.cmd.hand_back_abandon"
    const val ACTION_HAND_BACK_FINISH = "soundscape.cmd.hand_back_finish"

    // Commands / events sent from PlaybackService to MainActivity
    const val ACTION_RUN_IN_PAGE = "soundscape.event.run_in_page"
    const val ACTION_REPORT_TO_PAGE = "soundscape.event.report_to_page"
    const val ACTION_READ_PAGE = "soundscape.event.read_page"
    const val ACTION_NATIVE_STATE = "soundscape.event.native_state"

    const val KEY_STATE_JSON = "state_json"
    const val KEY_SERVER = "server"
    const val KEY_TOKEN = "token"
    const val KEY_READ_AT = "read_at"
    const val KEY_COMMAND_JSON = "command_json"
    const val KEY_REPORT_JSON = "report_json"
    const val KEY_TRACKS_JSON = "tracks_json"
    const val KEY_INDEX = "index"
    const val KEY_HAND_BACK_JSON = "hand_back_json"
    const val KEY_SCRIPT = "script"
    const val KEY_PLAYING_NATIVELY = "playing_natively"
    const val KEY_PLAYING_FOR_PAGE = "playing_for_page"

    val CMD_SHOW_PAGE = SessionCommand(ACTION_SHOW_PAGE, Bundle.EMPTY)
    val CMD_TAKE_OVER = SessionCommand(ACTION_TAKE_OVER, Bundle.EMPTY)
    val CMD_OBEY_PAGE = SessionCommand(ACTION_OBEY_PAGE, Bundle.EMPTY)
    val CMD_TELL_PAGE = SessionCommand(ACTION_TELL_PAGE, Bundle.EMPTY)
    val CMD_PLAY_DOWNLOADS = SessionCommand(ACTION_PLAY_DOWNLOADS, Bundle.EMPTY)
    val CMD_HAND_BACK_STATE = SessionCommand(ACTION_HAND_BACK_STATE, Bundle.EMPTY)
    val CMD_HAND_BACK_START = SessionCommand(ACTION_HAND_BACK_START, Bundle.EMPTY)
    val CMD_HAND_BACK_ABANDON = SessionCommand(ACTION_HAND_BACK_ABANDON, Bundle.EMPTY)
    val CMD_HAND_BACK_FINISH = SessionCommand(ACTION_HAND_BACK_FINISH, Bundle.EMPTY)

    val ALL_COMMANDS: List<SessionCommand> = listOf(
        CMD_SHOW_PAGE,
        CMD_TAKE_OVER,
        CMD_OBEY_PAGE,
        CMD_TELL_PAGE,
        CMD_PLAY_DOWNLOADS,
        CMD_HAND_BACK_STATE,
        CMD_HAND_BACK_START,
        CMD_HAND_BACK_ABANDON,
        CMD_HAND_BACK_FINISH,
    )

    val EVENT_RUN_IN_PAGE = SessionCommand(ACTION_RUN_IN_PAGE, Bundle.EMPTY)
    val EVENT_REPORT_TO_PAGE = SessionCommand(ACTION_REPORT_TO_PAGE, Bundle.EMPTY)
    val EVENT_READ_PAGE = SessionCommand(ACTION_READ_PAGE, Bundle.EMPTY)
    val EVENT_NATIVE_STATE = SessionCommand(ACTION_NATIVE_STATE, Bundle.EMPTY)
}
