package io.github.novakr12.soundscape

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeTest {
    private val server = "https://music.example.com"

    private val page = """
        {"tracks":[
          {"trackId":"band-one","songId":"nd-1","title":"One","artist":"Band","album":"Album","cover":"/api/navidrome/cover/al-1","duration":200,"level":0.5},
          {"trackId":"band-two","songId":null,"title":"Two","artist":"Band","album":null,"cover":null,"duration":null,"level":1}
        ],"position":1,"time":42.5,"duration":180,"playing":true,"volume":0.8,"repeat":"all"}
    """.trimIndent()

    @Test
    fun readsWhatThePageIsPlaying() {
        val state = Bridge.parse(page)
        assertNotNull(state)
        state!!
        assertEquals(2, state.tracks.size)
        assertEquals("band-two", state.current.trackId)
        assertEquals(42.5, state.time, 0.001)
        assertEquals(180.0, state.duration!!, 0.001)
        assertTrue(state.playing)
        assertEquals("nd-1", state.tracks[0].songId)
        assertNull(state.tracks[1].songId)
        assertNull(state.tracks[1].album)
        assertEquals(0.5, state.tracks[0].level, 0.001)
        assertEquals(Player.REPEAT_MODE_ALL, Bridge.repeatMode(state.repeat))
    }

    @Test
    fun refusesAPageItCannotTrust() {
        assertNull(Bridge.parse(null))
        assertNull(Bridge.parse("null"))
        assertNull(Bridge.parse("not json"))
        assertNull(Bridge.parse("""{"tracks":[],"position":0}"""))
        assertNull(Bridge.parse("""{"tracks":[{"trackId":"a","title":"A","artist":"B"}],"position":3}"""))
        assertNull(Bridge.parse("""{"tracks":[{"title":"No id"}],"position":0}"""))
    }

    @Test
    fun keepsLevelsAndVolumeInRange() {
        val loud = Bridge.parse("""{"tracks":[{"trackId":"a","title":"A","artist":"B","level":7}],"position":0,"volume":3}""")!!
        assertEquals(1.0, loud.tracks[0].level, 0.001)
        assertEquals(1.0, loud.volume, 0.001)
    }

    @Test
    fun streamsFullSongsAndLooksUpTheRest() {
        val state = Bridge.parse(page)!!
        assertEquals("$server/api/full-song/nd-1", Bridge.streamAddress(server, state.tracks[0]))
        assertEquals("$server/api/listen/band-two", Bridge.streamAddress(server, state.tracks[1]))
        val odd = state.tracks[1].copy(trackId = "a b/c")
        assertEquals("$server/api/listen/a%20b%2Fc", Bridge.streamAddress(server, odd))
    }

    @Test
    fun findsCoversOnTheServerOrElsewhere() {
        assertEquals("$server/api/cover/x", Bridge.coverAddress(server, "/api/cover/x"))
        assertEquals("https://cdn.example/a.jpg", Bridge.coverAddress(server, "https://cdn.example/a.jpg"))
        assertNull(Bridge.coverAddress(server, "//evil.example/a.jpg"))
        assertNull(Bridge.coverAddress(server, "javascript:alert(1)"))
        assertNull(Bridge.coverAddress(server, null))
    }

    @Test
    fun countsAPlayLikeTheRestOfSoundscape() {
        assertTrue(Bridge.countsAsPlay(100.0, 200.0))
        assertFalse(Bridge.countsAsPlay(99.0, 200.0))
        assertTrue(Bridge.countsAsPlay(240.0, 1200.0))
        assertFalse(Bridge.countsAsPlay(20.0, 25.0))
        assertFalse(Bridge.countsAsPlay(500.0, null))
        assertEquals(1.0, Bridge.heardStep(10.0, 11.0), 0.001)
        assertEquals(0.0, Bridge.heardStep(10.0, 40.0), 0.001)
        assertEquals(0.0, Bridge.heardStep(10.0, 5.0), 0.001)
        assertEquals(0.0, Bridge.heardStep(null, 5.0), 0.001)
    }

    @Test
    fun picksUpTheTokenOnlyFromItsOwnServer() {
        assertEquals(
            "abc" to "listener",
            Bridge.readToken(server, "$server/desktop/connected#token=abc&user=listener"),
        )
        assertEquals(
            "a+b" to "ana",
            Bridge.readToken(server, "soundscape-desktop://connected?token=a%2Bb&user=ana"),
        )
        assertNull(Bridge.readToken(server, "https://evil.example/desktop/connected#token=abc&user=x"))
        assertNull(Bridge.readToken(server, "$server/desktop/connected#token=&user=x"))
        assertNull(Bridge.readToken(server, "$server/stats#token=abc&user=x"))
    }

    @Test
    fun writesScriptsThePageUnderstands() {
        assertTrue(Bridge.command("next").contains("p.command(\"next\", undefined)"))
        assertTrue(Bridge.command("seek", 12.5).contains("p.command(\"seek\", 12.5)"))
        assertTrue(Bridge.handBack(HandBack(2, 61.234, true)).contains("{ position: 2, time: 61.2, playing: true }"))
        assertEquals(
            "$server/desktop/connect?device=Android%20%28Pixel%207%29&app=android",
            Bridge.connectAddress(server, "Android (Pixel 7)"),
        )
    }

    @Test
    fun catchesUpWithThePageWhileTheSongLoads() {
        assertEquals(84.2, Bridge.catchUp(83.4, 800, 200.0), 1e-9)
        assertEquals(10.0, Bridge.catchUp(10.0, -5, null), 1e-9)
        assertEquals(199.5, Bridge.catchUp(199.0, 3000, 200.0), 1e-9)
        assertEquals(3.0, Bridge.catchUp(-1.0, 3000, 0.2), 1e-9)
    }

    @Test
    fun nudgesThePhonePlayerIntoStepWithThePage() {
        assertTrue(Bridge.inStep(0.02))
        assertTrue(Bridge.inStep(-0.03))
        assertFalse(Bridge.inStep(0.05))
        assertEquals(1f, Bridge.nudge(0.01), 1e-6f)
        assertEquals(1.05f, Bridge.nudge(0.04), 1e-6f)
        assertEquals(1.2f, Bridge.nudge(0.4), 1e-6f)
        assertEquals(1.25f, Bridge.nudge(0.9), 1e-6f)
        assertEquals(0.9f, Bridge.nudge(-0.2), 1e-6f)
        assertFalse(Bridge.jumpNeeded(0.9))
        assertTrue(Bridge.jumpNeeded(1.5))
        assertTrue(Bridge.jumpNeeded(-3.0))
    }

    @Test
    fun handsBackWithTheMomentThePositionWasTaken() {
        val script = Bridge.handBack(HandBack(2, 61.23456, true, at = 1_700_000_000_000))
        assertTrue(script.contains("{ position: 2, time: 61.235, playing: true, at: 1700000000000 }"))
    }

    @Test
    fun waitsForThePageToFinishTakingBack() {
        val track = BridgeTrack("t1", null, "Song", "Band", null, null, 200.0, 1.0)
        val page = PageState(listOf(track), 0, 10.0, 200.0, true, 0.8, "off")
        assertFalse(Bridge.handBackDone(null))
        assertTrue(Bridge.handBackDone(page))
        assertFalse(Bridge.handBackDone(page.copy(handing = true)))
        assertTrue(Bridge.handBackDone(page.copy(handing = false)))
        assertFalse(Bridge.handBackDone(page.copy(handing = false, playing = false)))
    }

    @Test
    fun opensTheLocalChannelOnlyToTheServer() {
        assertEquals("https://music.example.com", Bridge.originRule("https://music.example.com/"))
        assertEquals("https://music.example.com:8443", Bridge.originRule("https://music.example.com:8443/app"))
        assertEquals("http://192.168.1.5:3000", Bridge.originRule(" http://192.168.1.5:3000 "))
        assertNull(Bridge.originRule("ftp://music.example.com"))
        assertNull(Bridge.originRule("not a server"))
    }

    @Test
    fun tellsThePageWhatThePhonesPlayerIsDoing() {
        val script = Bridge.nativeReport("""{"id":"phone0001","state":{"playing":true}}""")
        assertTrue(script.contains("window.soundscapeNativeReport"))
        assertTrue(script.contains("""r({"id":"phone0001","state":{"playing":true}})"""))
    }

    @Test
    fun timesAPageReadFromTheMiddleOfItsRoundTrip() {
        assertEquals(1050L, Bridge.readMoment(1000, 1100))
        assertEquals(1000L, Bridge.readMoment(1000, 1000))
        assertEquals(1000L, Bridge.readMoment(1000, 990))
    }
}
