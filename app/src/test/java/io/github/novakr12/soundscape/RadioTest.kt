package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioTest {
    @Test
    fun asksForMoreOnlyNearTheEndWithAutoplayOn() {
        assertTrue(Radio.needsMore(autoplay = true, repeatOff = true, left = 0))
        assertTrue(Radio.needsMore(autoplay = true, repeatOff = true, left = 2))
        assertFalse(Radio.needsMore(autoplay = true, repeatOff = true, left = 3))
        assertFalse(Radio.needsMore(autoplay = false, repeatOff = true, left = 0))
        assertFalse(Radio.needsMore(autoplay = true, repeatOff = false, left = 0))
    }

    @Test
    fun sendsTheSongAndWhatIsAlreadyQueued() {
        val seed = Radio.songs("""{"songs":[{"trackId":"band-one","songId":"nd-1","title":"One","artist":"Band"}]}""")[0]
        val body = JSONObject(Radio.body(seed, listOf("nd-1", "nd-2"), listOf("earlier", "band-one")))
        assertEquals("band-one", body.getString("trackId"))
        assertEquals("Band", body.getString("artist"))
        assertEquals("One", body.getString("title"))
        assertTrue(body.getBoolean("continuing"))
        assertEquals(2, body.getJSONArray("exclude").length())
        assertEquals("nd-2", body.getJSONArray("exclude").getString(1))
        assertEquals("earlier", body.getJSONArray("skipTracks").getString(0))
    }

    @Test
    fun readsTheRadioSongs() {
        val songs = Radio.songs(
            """{"songs":[
              {"trackId":"band-two","songId":"nd-2","title":"Two","artist":"Band","album":"Album","duration":90},
              {"title":"No id"}
            ]}""",
        )
        assertEquals(1, songs.size)
        assertEquals("nd-2", songs[0].songId)
        assertEquals("Album", songs[0].album)
        assertEquals(emptyList<BridgeTrack>(), Radio.songs("not json"))
    }
}
