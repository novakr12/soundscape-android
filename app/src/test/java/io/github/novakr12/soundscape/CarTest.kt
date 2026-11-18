package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarTest {
    private val album = """{"connected":true,"title":"Road Album","items":[
        {"kind":"song","track":{"trackId":"t1","songId":"car-1","title":"First Gear","artist":"Band","album":"Road Album","cover":"/api/navidrome/cover/road","duration":180,"level":0.6}},
        {"kind":"song","track":{"trackId":"t2","songId":null,"title":"Second Gear","artist":"Band","album":null,"cover":null,"duration":null,"level":1}},
        {"kind":"song","track":{"trackId":"t3"}},
        {"kind":"weird"}
    ]}"""

    @Test
    fun readsALibraryPage() {
        val page = Car.page(album)!!
        assertTrue(page.connected)
        assertEquals("Road Album", page.title)
        assertEquals(listOf("t1", "t2"), page.songs.map { it.trackId })
        assertEquals(0.6, page.songs[0].level, 0.0)
        assertNull(page.songs[1].songId)
        val folders = Car.page(
            """{"connected":true,"title":"New albums","items":[
                {"kind":"folder","node":"album:road","title":"Road Album","subtitle":"Band","cover":"/api/navidrome/cover/road","playable":true},
                {"kind":"folder","node":"album:../x","title":"Bad"}
            ]}""",
        )!!
        assertEquals(1, folders.items.size)
        val folder = folders.items[0] as CarEntry.Folder
        assertEquals("album:road", folder.node)
        assertTrue(folder.playable)
        assertFalse(Car.page("""{"connected":false,"title":"Soundscape","items":[]}""")!!.connected)
        assertNull(Car.page("nope"))
        assertNull(Car.page(null))
    }

    @Test
    fun namesSongsByWhereTheyWereFound() {
        assertEquals("song|album:road|3", Car.songId("album:road", 3))
        assertEquals("album:road" to 3, Car.readSongId("song|album:road|3"))
        assertEquals("search:a|b" to 0, Car.readSongId("song|search:a|b|0"))
        assertEquals("downloads" to 2, Car.readSongId("song|downloads|2"))
        assertNull(Car.readSongId("song|album:road|-1"))
        assertNull(Car.readSongId("song|nowhere|1"))
        assertNull(Car.readSongId("album:road"))
        assertTrue(Car.isPlayableFolder("album:road"))
        assertTrue(Car.isPlayableFolder("shuffle"))
        assertFalse(Car.isPlayableFolder("albums:added"))
    }

    @Test
    fun asksTheServerForTheRightPlace() {
        assertEquals("https://music.example/api/app/library?node=album%3Aroad", Car.address("https://music.example", "album:road"))
        assertEquals("https://music.example/api/app/library?search=daft%20punk", Car.address("https://music.example", Car.searchNode(" daft punk ")))
        assertTrue(Car.isNode("albums:played"))
        assertFalse(Car.isNode("albums:year"))
        assertFalse(Car.isNode("search:"))
    }

    @Test
    fun servesOnlyNavidromeCoversToTheCar() {
        assertEquals(
            "content://io.github.novakr12.soundscape.covers/cover?path=%2Fapi%2Fnavidrome%2Fcover%2Fal-1",
            Car.coverUri("io.github.novakr12.soundscape.covers", "/api/navidrome/cover/al-1"),
        )
        assertNull(Car.coverUri("a", "/api/navidrome/cover/../../token"))
        assertNull(Car.coverUri("a", "https://elsewhere.example/x.jpg"))
        assertNull(Car.coverUri("a", null))
    }

    @Test
    fun carriesTheSongInsideThePlayerItem() {
        val track = Car.page(album)!!.songs[0]
        assertEquals(track, Car.trackFrom(Car.trackJson(track)))
        val bare = track.copy(songId = null, album = null, cover = null, duration = null)
        assertEquals(bare, Car.trackFrom(Car.trackJson(bare)))
        assertNull(Car.trackFrom("""{"trackId":"x"}"""))
        assertNull(Car.trackFrom(null))
    }
}
