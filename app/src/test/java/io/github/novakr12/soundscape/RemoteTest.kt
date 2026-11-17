package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RemoteTest {
    private val song = BridgeTrack("t1", "nav-1", "Song", "Band", "Record", "/api/cover/t1", 200.0, 0.7)

    @Test
    fun readsTheCommandsMeantForThePhone() {
        val commands = Remote.commands(
            """{"commands":[
                {"do":"pause"},{"do":"next"},{"do":"seek","time":42.5},{"do":"seek","time":-1},
                {"do":"volume","volume":0.4},{"do":"volume","volume":4},{"do":"send","to":"desktop0001"},
                {"do":"send","to":"x"},{"do":"format"},
                {"do":"take","position":1,"time":12,"tracks":[
                    {"trackId":"a","title":"One","artist":"Band"},
                    {"trackId":"b","title":"Two","artist":"Band","songId":"nav-2","duration":180},
                    {"trackId":"c"}
                ]}
            ]}""",
        )
        assertEquals(RemoteCommand.Pause, commands[0])
        assertEquals(RemoteCommand.Next, commands[1])
        assertEquals(RemoteCommand.Seek(42.5), commands[2])
        assertEquals(RemoteCommand.Volume(0.4), commands[3])
        assertEquals(RemoteCommand.Send("desktop0001"), commands[4])
        val take = commands[5] as RemoteCommand.Take
        assertEquals(6, commands.size)
        assertEquals(listOf("a", "b"), take.tracks.map { it.trackId })
        assertEquals("nav-2", take.tracks[1].songId)
        assertEquals(1, take.position)
        assertEquals(12.0, take.time, 0.0)
        assertTrue(take.playing)
        assertTrue(Remote.commands("not json").isEmpty())
        assertTrue(Remote.commands(null).isEmpty())
        assertNull(Remote.command(JSONObject("""{"do":"take","position":3,"tracks":[{"trackId":"a","title":"One","artist":"Band"}]}""")))
    }

    @Test
    fun reportsWhatThePhonePlays() {
        val report = JSONObject(Remote.report("phone000001", "Soundscape on Pixel", RemoteSnapshot(song, true, 30.5, 200.0, 0.6)))
        assertEquals("phone000001", report.getString("id"))
        assertEquals("android", report.getString("kind"))
        val state = report.getJSONObject("state")
        assertTrue(state.getBoolean("playing"))
        assertEquals(30.5, state.getDouble("time"), 0.0)
        assertEquals("nav-1", state.getJSONObject("track").getString("songId"))
        val idle = JSONObject(Remote.report("phone000001", "x", RemoteSnapshot(null, true, -2.0, null, 3.0))).getJSONObject("state")
        assertFalse(idle.getBoolean("playing"))
        assertTrue(idle.isNull("track"))
        assertTrue(idle.isNull("duration"))
        assertEquals(1.0, idle.getDouble("volume"), 0.0)
    }

    @Test
    fun handsTheQueueToAnotherDevice() {
        val tracks = (1..3).map { song.copy(trackId = "t$it", songId = null, album = null) }
        val body = JSONObject(Remote.send("desktop0001", tracks, 2, 15.0)!!)
        assertEquals("desktop0001", body.getString("to"))
        val command = body.getJSONObject("command")
        assertEquals("take", command.getString("do"))
        assertEquals(2, command.getInt("position"))
        assertEquals(3, command.getJSONArray("tracks").length())
        assertFalse(command.getJSONArray("tracks").getJSONObject(0).has("songId"))
        assertNull(Remote.send("desktop0001", emptyList(), 0, 0.0))
        assertNull(Remote.send("bad", tracks, 0, 0.0))
        val many = (1..900).map { song.copy(trackId = "t$it") }
        val cut = JSONObject(Remote.send("desktop0001", many, 800, 0.0)!!).getJSONObject("command")
        val position = cut.getInt("position")
        assertEquals("t801", cut.getJSONArray("tracks").getJSONObject(position).getString("trackId"))
    }

    @Test
    fun readsTheLiveStream() {
        val reader = EventReader()
        val lines = listOf(": connected", "", "event: command", "data: {\"to\":\"phone000001\"}", "", "data: x", "")
        val events = lines.mapNotNull { reader.line(it) }
        assertEquals(listOf("command" to "{\"to\":\"phone000001\"}", "message" to "x"), events)
        assertTrue(Remote.isForMe(events[0].second, "phone000001"))
        assertFalse(Remote.isForMe(events[0].second, "other000001"))
        assertFalse(Remote.isForMe("nope", "phone000001"))
    }

    @Test
    fun namesThePhoneForThePage() {
        val id = Remote.newDeviceId(Random(1))
        assertTrue(Remote.isDeviceId(id))
        assertFalse(Remote.isDeviceId("short"))
        assertEquals("Soundscape on Pixel 8", Remote.deviceName("Pixel 8"))
        assertEquals("Soundscape on Android", Remote.deviceName(" "))
        val script = Remote.pageScript(id, "Soundscape on \"Test\"</script>")
        assertTrue(script.startsWith("window.soundscapeDeviceId = \"$id\";"))
        assertTrue(script.contains("\\\"Test\\\""))
        assertTrue(Remote.holdScript(false).contains("soundscape-device-report"))
        assertEquals("https://music.example.com:8443", Server.origin("https://Music.Example.com:8443/soundscape"))
        assertEquals("http://192.168.1.5", Server.origin("http://192.168.1.5"))
        assertNull(Server.origin("ftp://x"))
    }

    @Test
    fun handsANewQueueBackToThePage() {
        val back = HandBack(1, 12.34, true, listOf(song, song.copy(trackId = "t2")))
        val script = Bridge.handBack(back)
        assertTrue(script.contains("position: 1, time: 12.3, playing: true, tracks: ["))
        assertTrue(script.contains("\"trackId\":\"t2\""))
        assertFalse(Bridge.handBack(HandBack(0, 1.0, false)).contains("tracks"))
    }
}
