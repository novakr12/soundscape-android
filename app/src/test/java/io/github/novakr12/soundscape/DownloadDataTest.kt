package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDataTest {
    @Test
    fun readsWhatThePageAsksFor() {
        val ask = DownloadData.parse(
            """{"page":"p1","asked":[
                {"do":"download","tracks":[
                  {"trackId":"band-one","songId":"nd-1","title":"One","artist":"Band","album":"Album","duration":200},
                  {"trackId":"","title":"No id"},
                  {"trackId":"band-two","title":"Two","artist":"Band"}
                ]},
                {"do":"remove","trackIds":["band-one",""]},
                {"do":"download","tracks":[]},
                {"do":"wipe"}
            ]}""",
        )!!
        assertEquals("p1", ask.page)
        assertEquals(2, ask.requests.size)
        assertEquals(listOf("band-one", "band-two"), ask.requests[0].tracks.map { it.trackId })
        assertEquals("nd-1", ask.requests[0].tracks[0].songId)
        assertNull(ask.requests[0].tracks[1].songId)
        assertEquals(listOf("band-one"), ask.requests[1].trackIds)
        assertNull(DownloadData.parse("null"))
        assertTrue(DownloadData.parse("""{"page":"p"}""")!!.requests.isEmpty())
    }

    @Test
    fun keepsWhatItNeedsToPlayOffline() {
        val track = BridgeTrack("band-one", "nd-1", "One", "Band", "Album", "/api/cover/x", 200.0, 0.5)
        assertEquals(track, DownloadData.decode(DownloadData.encode(track)))
        val bare = BridgeTrack("band-two", null, "Two", "Band", null, null, null, 1.0)
        assertEquals(bare, DownloadData.decode(DownloadData.encode(bare)))
        assertNull(DownloadData.decode("broken".toByteArray()))
        assertEquals("Album", DownloadData.groupName(track))
        assertEquals("Songs", DownloadData.groupName(bare))
    }

    @Test
    fun tellsThePageWhatIsOnThePhone() {
        val script = DownloadData.push("0.5.0", mapOf("a" to "done", "b" to "waiting"))
        assertTrue(script.contains("soundscape-android"))
        val json = script.substringAfter("JSON.parse(").substringBefore("); window")
        val detail = JSONObject(JSONObject("{\"v\":$json}").getString("v"))
        assertEquals("0.5.0", detail.getString("version"))
        assertEquals("done", detail.getJSONObject("downloads").getString("a"))
        assertFalse(DownloadData.push("1", mapOf("</script>" to "done")).contains("</script>"))
    }

    @Test
    fun sumsUpTheSpaceUsed() {
        assertNull(DownloadData.summary(0, 0))
        assertEquals("1 song · 5 MB", DownloadData.summary(1, 4_600_000))
        assertEquals("12 songs · 84 MB", DownloadData.summary(12, 84_000_000))
        assertEquals("300 songs · 2.4 GB", DownloadData.summary(300, 2_400_000_000))
    }
}
