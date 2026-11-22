package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackBridgeTest {
    private val first = BridgeTrack("t1", "s1", "One", "Band", "Album", "/api/cover/t1", 210.5, 0.75)
    private val second = BridgeTrack("t2", null, "Two", "Band", null, null, null, 1.0)

    @Test
    fun roundTripsPageStateThroughBridgeParser() {
        val state = PageState(
            tracks = listOf(first, second),
            position = 1,
            time = 42.5,
            duration = 190.0,
            playing = true,
            volume = 0.65,
            repeat = "all",
            remote = "Living room",
            handing = true,
        )
        assertEquals(state, Bridge.parse(PlaybackCodecs.encodePageState(state)))

        val minimal = PageState(
            tracks = listOf(second),
            position = 0,
            time = 0.0,
            duration = null,
            playing = false,
            volume = 1.0,
            repeat = "off",
        )
        assertEquals(minimal, Bridge.parse(PlaybackCodecs.encodePageState(minimal)))
    }

    @Test
    fun roundTripsHandBack() {
        val full = HandBack(1, 31.25, true, listOf(first, second), 1_700_000_000_000L)
        assertEquals(full, PlaybackCodecs.decodeHandBack(PlaybackCodecs.encodeHandBack(full)))

        val plain = HandBack(0, 0.0, false)
        assertEquals(plain, PlaybackCodecs.decodeHandBack(PlaybackCodecs.encodeHandBack(plain)))
        assertNull(PlaybackCodecs.decodeHandBack("bad json"))
    }

    @Test
    fun roundTripsTracksStoredListAndStateMap() {
        val tracks = listOf(first, second)
        assertEquals(tracks, PlaybackCodecs.decodeTracks(PlaybackCodecs.encodeTracks(tracks)))

        val stored = listOf(
            Stored(first, 3, 4_200_000L, 100.0f),
            Stored(second, 2, 128_000L, -1.0f),
        )
        assertEquals(stored, PlaybackCodecs.decodeStoredList(PlaybackCodecs.encodeStoredList(stored)))

        val states = mapOf("t1" to "done", "t2" to "waiting")
        assertEquals(states, PlaybackCodecs.decodeStringMap(PlaybackCodecs.encodeStringMap(states)))
    }
}
