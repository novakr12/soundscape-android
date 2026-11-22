package io.github.novakr12.soundscape

import androidx.media3.common.Player
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

data class BridgeTrack(
    val trackId: String,
    val songId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val cover: String?,
    val duration: Double?,
    val level: Double,
)

data class PageState(
    val tracks: List<BridgeTrack>,
    val position: Int,
    val time: Double,
    val duration: Double?,
    val playing: Boolean,
    val volume: Double,
    val repeat: String,
    val remote: String? = null,
    val handing: Boolean? = null,
) {
    val current: BridgeTrack get() = tracks[position]
}

data class HandBack(
    val position: Int,
    val time: Double,
    val playing: Boolean,
    val tracks: List<BridgeTrack>? = null,
    val at: Long? = null,
)

object Bridge {
    const val READ =
        "(() => { const p = window.soundscapePlayer; return p && p.version === 1 ? p.state() : null; })()"

    private const val SHORTEST_SONG_SECONDS = 30.0
    private const val LONGEST_WAIT_SECONDS = 240.0
    private const val LONGEST_STEP_SECONDS = 2.0
    private const val CATCH_UP_MARGIN = 0.5
    private const val IN_STEP_SECONDS = 0.03
    private const val JUMP_ABOVE_SECONDS = 1.0
    private const val GENTLEST_NUDGE = 0.05
    private const val STRONGEST_NUDGE = 0.25

    fun command(name: String, value: Double? = null): String {
        val argument = value?.takeIf { it.isFinite() }?.toString() ?: "undefined"
        return "(() => { const p = window.soundscapePlayer; return p ? p.command(${JSONObject.quote(name)}, $argument) : false; })()"
    }

    fun handBack(back: HandBack): String {
        val time = if (back.at != null) {
            Math.round(back.time.coerceAtLeast(0.0) * 1000) / 1000.0
        } else {
            Math.round(back.time.coerceAtLeast(0.0) * 10) / 10.0
        }
        val payload = JSONObject()
            .put("position", back.position)
            .put("time", time)
            .put("playing", back.playing)
        back.at?.let { payload.put("at", it) }
        back.tracks?.let { payload.put("tracks", Remote.handed(it)) }
        return "(() => { const p = window.soundscapePlayer; return p ? p.handBack(JSON.parse(${JSONObject.quote(payload.toString())})) : false; })()"
    }

    fun parse(json: String?): PageState? {
        if (json.isNullOrBlank() || json == "null") return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val list = root.optJSONArray("tracks") ?: return null
        val tracks = (0 until list.length()).mapNotNull { index ->
            TrackJson.decode(list.optJSONObject(index))
        }
        if (tracks.size != list.length() || tracks.isEmpty()) return null
        val position = root.optInt("position", -1)
        if (position !in tracks.indices) return null
        return PageState(
            tracks = tracks,
            position = position,
            time = root.optDouble("time", 0.0).takeIf { it.isFinite() && it > 0 } ?: 0.0,
            duration = root.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            playing = root.optBoolean("playing", false),
            volume = root.optDouble("volume", 0.8).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.8,
            repeat = root.optString("repeat", "off"),
            remote = root.optJSONObject("remote")?.optionalText("name"),
            handing = if (root.has("handing")) root.optBoolean("handing") else null,
        )
    }

    const val NATIVE_PAGE =
        "(() => { const p = window.soundscapePlayer; return Boolean(p && p.native === true); })()"

    fun spectrum(bins: ByteArray): String =
        bins.joinToString(
            separator = ",",
            prefix = "window.dispatchEvent(new CustomEvent(\"soundscape-spectrum-bins\", { detail: [",
            postfix = "] })), undefined",
        ) { (it.toInt() and 0xff).toString() }

    fun nativeReport(json: String) =
        "(() => { const r = window.soundscapeNativeReport; if (r) r(JSON.parse(${JSONObject.quote(json)})); })()"

    fun originRule(server: String): String? = Server.origin(server)

    fun handBackDone(page: PageState?): Boolean {
        if (page == null) return false
        val handing = page.handing ?: return true
        return page.playing && !handing
    }

    fun readMoment(asked: Long, answered: Long) = asked + (answered - asked).coerceAtLeast(0) / 2

    fun inStep(behind: Double) = kotlin.math.abs(behind) <= IN_STEP_SECONDS

    fun jumpNeeded(behind: Double) = kotlin.math.abs(behind) > JUMP_ABOVE_SECONDS

    fun nudge(behind: Double): Float {
        if (inStep(behind)) return 1f
        val strength = (kotlin.math.abs(behind) * 0.5).coerceIn(GENTLEST_NUDGE, STRONGEST_NUDGE)
        return (if (behind > 0) 1 + strength else 1 - strength).toFloat()
    }

    fun catchUp(time: Double, elapsedMs: Long, duration: Double?): Double {
        val moved = time.coerceAtLeast(0.0) + elapsedMs.coerceAtLeast(0) / 1000.0
        val end = duration?.takeIf { it.isFinite() && it > CATCH_UP_MARGIN }?.minus(CATCH_UP_MARGIN)
        return if (end != null) minOf(moved, end) else moved
    }

    fun lockScreen(state: PageState, where: (String) -> String): PageState {
        val elsewhere = state.remote ?: return state
        val track = state.current.copy(album = where(elsewhere))
        return state.copy(tracks = listOf(track, track, track), position = 1, repeat = "off")
    }

    fun streamAddress(server: String, track: BridgeTrack): String = if (track.songId != null) {
        "$server/api/full-song/${encodeQueryPart(track.songId)}"
    } else {
        "$server/api/listen/${encodeQueryPart(track.trackId)}"
    }

    fun coverAddress(server: String, cover: String?): String? = when {
        cover == null -> null
        cover.startsWith("https://") || cover.startsWith("http://") -> cover
        cover.startsWith("/") && !cover.startsWith("//") -> {
            val base = Server.basePath(server)
            val origin = Server.origin(server)
            if (base.isNotEmpty() && origin != null && (cover == base || cover.startsWith("$base/"))) {
                "$origin$cover"
            } else {
                "$server$cover"
            }
        }
        else -> null
    }

    fun repeatMode(repeat: String): Int = when (repeat) {
        "all" -> Player.REPEAT_MODE_ALL
        "one" -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    }

    fun countsAsPlay(heard: Double, length: Double?): Boolean =
        length != null && length.isFinite() && length >= SHORTEST_SONG_SECONDS &&
            heard >= minOf(length / 2.0, LONGEST_WAIT_SECONDS)

    fun heardStep(before: Double?, now: Double): Double =
        if (before != null && now > before && now - before <= LONGEST_STEP_SECONDS) now - before else 0.0

    fun connectAddress(server: String, device: String) =
        "$server/desktop/connect?device=${encodeQueryPart(device)}&app=android"

    fun readToken(server: String, address: String): Pair<String, String>? {
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        val connectedPath = "${Server.basePath(server)}/desktop/connected"
        val pairs = when {
            uri.scheme == "soundscape-desktop" && uri.host == "connected" -> uri.rawQuery
            Server.isOwnPage(server, address) && uri.rawPath == connectedPath -> uri.rawFragment
            else -> null
        } ?: return null
        val values = pairs.split("&").mapNotNull { pair ->
            val at = pair.indexOf('=')
            if (at <= 0) {
                null
            } else {
                runCatching { decode(pair.substring(0, at)) to decode(pair.substring(at + 1)) }.getOrNull()
            }
        }.toMap()
        val token = values["token"]?.takeIf { it.isNotBlank() } ?: return null
        val user = values["user"]?.takeIf { it.isNotBlank() } ?: return null
        return token to user
    }

    private fun decode(text: String) = URLDecoder.decode(text, "UTF-8")
}
