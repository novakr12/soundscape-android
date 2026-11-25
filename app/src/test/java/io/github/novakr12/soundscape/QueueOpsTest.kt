package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class QueueOpsTest {
    private fun track(id: String, songId: String? = "s-$id") =
        BridgeTrack(id, songId, "Song $id", "Band", "Album", "/api/cover/$id", 180.0, 0.9)

    @Test
    fun removesAndMovesUpcomingTracksSafely() {
        val list = listOf(track("1"), track("2"), track("3"), track("4"))
        val unshuffled = listOf(track("3"), track("4"), track("2"))

        assertNull(QueueOps.removeUpcoming(list, 0, 0, unshuffled))
        assertNull(QueueOps.removeUpcoming(list, 0, 4, unshuffled))
        val removed = QueueOps.removeUpcoming(list, 0, 2, unshuffled)!!
        assertEquals(2, removed.targetIndex)
        assertEquals(listOf("1", "2", "4"), removed.tracks.map { it.trackId })
        assertEquals(listOf("4", "2"), removed.unshuffled?.map { it.trackId })

        assertNull(QueueOps.moveUpcoming(list, 0, 0, 1))
        assertNull(QueueOps.moveUpcoming(list, 0, 2, 2))
        assertNull(QueueOps.moveUpcoming(list, 0, 1, 4))

        val movedForward = QueueOps.moveUpcoming(list, 0, 1, 3)!!
        assertEquals(1, movedForward.fromIndex)
        assertEquals(3, movedForward.toIndex)
        assertEquals(listOf("1", "3", "4", "2"), movedForward.tracks.map { it.trackId })

        val movedBackward = QueueOps.moveUpcoming(list, 0, 3, 1)!!
        assertEquals(listOf("1", "4", "2", "3"), movedBackward.tracks.map { it.trackId })
    }

    @Test
    fun windowsLargeQueuesAndRoundTripsSavedQueueFile() {
        val big = (1..900).map { track("t$it") }
        val (part, pos) = QueueOps.window(big, 800)!!
        assertEquals(500, part.size)
        assertEquals("t801", part[pos].trackId)
        assertNull(QueueOps.window(emptyList(), 0))
        assertNull(QueueOps.window(big, 900))

        val server = "https://music.example.com"
        val saved = SavedQueue(
            server = server,
            tracks = listOf(track("a"), track("b"), track("c")),
            position = 1,
            time = 42.5,
            repeat = "all",
            shuffle = true,
            unshuffled = listOf(track("c")),
        )
        assertNull(QueueOps.decodeSavedQueue(QueueOps.encodeSavedQueue(saved), "https://other.example.com"))

        val dir = Files.createTempDirectory("soundscape-queue-test").toFile()
        try {
            QueueOps.saveFile(dir, saved)
            assertEquals(saved, QueueOps.loadFile(dir, server))
            QueueOps.clearFile(dir)
            assertNull(QueueOps.loadFile(dir, server))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun buildsPlaylistRequestAndFormatsSaveResults() {
        val body = JSONObject(QueueOps.playlistRequestBody("Late Night", listOf(track("a", "nav-1"), track("b", null))))
        assertEquals("Late Night", body.getString("name"))
        assertEquals("nav-1", body.getJSONArray("tracks").getJSONObject(0).getString("songId"))
        assertFalse(body.getJSONArray("tracks").getJSONObject(1).has("songId"))

        val ok = JSONObject(
            QueueOps.playlistResultDetail(
                "req-1",
                "Fallback",
                HttpAnswer(200, """{"name":"Late Night","saved":2,"total":3,"missing":1}"""),
            ),
        )
        assertTrue(ok.getBoolean("ok"))
        assertEquals("req-1", ok.getString("id"))
        assertEquals("Late Night", ok.getString("name"))
        assertEquals(2, ok.getInt("saved"))
        assertEquals(3, ok.getInt("total"))
        assertEquals(1, ok.getInt("missing"))

        val serverErr = JSONObject(
            QueueOps.playlistResultDetail("req-2", "Mix", HttpAnswer(409, """{"error":"Connect Navidrome first"}""")),
        )
        assertFalse(serverErr.getBoolean("ok"))
        assertEquals("Connect Navidrome first", serverErr.getString("error"))

        val unreachable = JSONObject(QueueOps.playlistResultDetail("req-3", "Mix", null))
        assertFalse(unreachable.getBoolean("ok"))
        assertEquals("Could not reach Soundscape.", unreachable.getString("error"))
    }
}
