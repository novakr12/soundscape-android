package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordTest {
    private fun song() = DiscordSong(
        title = "Need It",
        artist = "Migos",
        album = "Culture III",
    )

    @Test
    fun checksTheApplicationId() {
        assertTrue(Discord.validAppId("1234567890123456789"))
        assertTrue(Discord.validAppId("12345678901234567"))
        assertFalse(Discord.validAppId("12345"))
        assertFalse(Discord.validAppId("12345678901234567x9"))
        assertFalse(Discord.validAppId("99999999999999999999"))
        assertFalse(Discord.validAppId(""))
    }

    @Test
    fun describesTheSongLikeAMusicApp() {
        val shown = DiscordShown(
            song = song(),
            startMs = 1_700_000_000_000L,
            endMs = 1_700_000_200_000L,
            image = "https://cdn.example/cover.jpg",
            album = "Culture III",
        )
        val message = JSONObject(Discord.activity(shown, 42, 7))
        assertEquals("SET_ACTIVITY", message.getString("cmd"))
        assertEquals("7", message.getString("nonce"))
        assertEquals(42, message.getJSONObject("args").getInt("pid"))
        val sent = message.getJSONObject("args").getJSONObject("activity")
        assertEquals("Soundscape", sent.getString("name"))
        assertEquals(2, sent.getInt("type"))
        assertEquals(0, sent.getInt("status_display_type"))
        assertEquals("Need It", sent.getString("details"))
        assertEquals("by Migos", sent.getString("state"))
        assertEquals(1_700_000_000L, sent.getJSONObject("timestamps").getLong("start"))
        assertEquals(1_700_000_200L, sent.getJSONObject("timestamps").getLong("end"))
        assertEquals("https://cdn.example/cover.jpg", sent.getJSONObject("assets").getString("large_image"))
        assertEquals("Culture III", sent.getJSONObject("assets").getString("large_text"))

        assertTrue(JSONObject(Discord.activity(null, 42, 8)).getJSONObject("args").isNull("activity"))

        val openEnded = shown.copy(endMs = null)
        val openTimestamps = JSONObject(Discord.activity(openEnded, 1, 1))
            .getJSONObject("args")
            .getJSONObject("activity")
            .getJSONObject("timestamps")
        assertEquals(1_700_000_000L, openTimestamps.getLong("start"))
        assertFalse(openTimestamps.has("end"))
    }

    @Test
    fun padsShortTitlesAndTrimsLongOnes() {
        val padded = Discord.short("7")
        assertEquals(2, padded.length)
        assertTrue(padded.startsWith("7"))
        assertEquals(128, Discord.short("a".repeat(200)).length)
    }

    @Test
    fun alwaysNamesAnAlbum() {
        assertEquals("Culture III", Discord.albumLabel("Culture III", null))
        assertEquals("Culture III", Discord.albumLabel("Soundscape", "Culture III"))
        assertEquals("Culture III", Discord.albumLabel(null, "Culture III"))
        assertEquals("Unknown album", Discord.albumLabel("30-second preview", null))
        assertEquals("Unknown album", Discord.albumLabel("  ", ""))
        assertEquals("Unknown album", Discord.albumLabel(null, null))
    }

    @Test
    fun worksOutWhereTheSongStartedAndEnds() {
        assertEquals(70_000L to 270_000L, Discord.timing(100_000L, 30.0, 200.0))
        assertEquals(100_000L to null, Discord.timing(100_000L, null, null))
        assertEquals(0L to null, Discord.timing(10_000L, 30.0, 0.0))
    }

    @Test
    fun onlyUsesCoversAnyoneCanSee() {
        assertEquals(
            "https://i.scdn.co/image/abc",
            Discord.publicArt("https://i.scdn.co/image/abc", "music.example"),
        )
        assertNull(Discord.publicArt("https://music.example/api/navidrome/cover/1", "music.example"))
        assertNull(Discord.publicArt("file:///home/me/cover.jpg", "music.example"))
        assertNull(Discord.publicArt("http://cdn.example/a.jpg", null))
        assertNull(Discord.publicArt("/api/cover/1", "music.example"))
        assertNull(Discord.publicArt(null, null))
    }

    @Test
    fun sendsAgainOnlyWhenSomethingShowsDifferently() {
        val first = DiscordShown(
            song = song(),
            startMs = 100_000L,
            endMs = 300_000L,
            image = Discord.LOGO,
            album = Discord.UNKNOWN_ALBUM,
        )
        val aMomentLater = first.copy(startMs = 100_900L, endMs = 300_900L)
        val afterASeek = first.copy(startMs = 60_000L, endMs = 260_000L)
        assertFalse(Discord.needsUpdate(first, aMomentLater))
        assertTrue(Discord.needsUpdate(first, afterASeek))
        assertTrue(Discord.needsUpdate(first, null))
        assertTrue(Discord.needsUpdate(null, first))
        assertFalse(Discord.needsUpdate(null, null))
        assertTrue(Discord.needsUpdate(first, first.copy(song = song().copy(title = "Bad and Boujee"))))
        assertTrue(Discord.needsUpdate(first, first.copy(album = "Culture III")))
        assertFalse(Discord.needsUpdate(first, first.copy(endMs = 301_500L)))
        assertTrue(Discord.needsUpdate(first, first.copy(endMs = 360_000L)))
        assertTrue(Discord.needsUpdate(first, first.copy(endMs = null)))
        assertFalse(Discord.needsUpdate(first.copy(endMs = null), first.copy(endMs = null)))
    }

    @Test
    fun parsesCoverLinksAndBuildsQueryAddresses() {
        assertEquals(
            "https://music.example/api/cover-link?artist=Daft%20Punk&title=One%20More%20Time",
            Discord.coverLinkAddress("https://music.example", "Daft Punk", "One More Time"),
        )
        assertEquals(
            "https://music.example/api/cover/1" to "Discovery",
            Discord.parseCoverLink("""{"url":"https://music.example/api/cover/1","album":"Discovery"}"""),
        )
        assertEquals(
            null to "Discovery",
            Discord.parseCoverLink("""{"url":"http://insecure.example/a.jpg","album":"Discovery"}"""),
        )
        assertEquals(null to null, Discord.parseCoverLink("""{"url":null,"album":null}"""))
    }
}
