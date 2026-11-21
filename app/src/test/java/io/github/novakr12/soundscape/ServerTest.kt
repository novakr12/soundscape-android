package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTest {
    @Test
    fun addsHttpsWhenTheAddressHasNoScheme() {
        assertEquals("https://music.example.com", Server.normalize("music.example.com"))
        assertEquals("https://music.example.com", Server.normalize("  Music.Example.com/  "))
    }

    @Test
    fun keepsPortsPathsAndPlainHttp() {
        assertEquals("http://192.168.1.20:3000", Server.normalize("http://192.168.1.20:3000/"))
        assertEquals("https://example.com/soundscape", Server.normalize("https://example.com/soundscape/"))
    }

    @Test
    fun refusesAddressesItCannotUse() {
        assertNull(Server.normalize(""))
        assertNull(Server.normalize("not an address"))
        assertNull(Server.normalize("ftp://example.com"))
        assertNull(Server.normalize("https://example.com/?page=1"))
        assertNull(Server.normalize("https://user:secret@example.com"))
        assertNull(Server.normalize("javascript:alert(1)"))
    }

    @Test
    fun keepsOnlyTheServersOwnPagesInTheApp() {
        val server = "https://music.example.com"
        assertTrue(Server.isOwnPage(server, "https://music.example.com/stats"))
        assertTrue(Server.isOwnPage(server, "https://MUSIC.example.com:443/u/ana"))
        assertFalse(Server.isOwnPage(server, "http://music.example.com/stats"))
        assertFalse(Server.isOwnPage(server, "https://musicbrainz.org/artist/x"))
        assertFalse(Server.isOwnPage(server, "https://music.example.com.evil.test/"))
        assertFalse(Server.isOwnPage("http://10.0.0.5:3000", "http://10.0.0.5:4000/"))
        assertTrue(Server.isOwnPage("http://10.0.0.5:3000", "http://10.0.0.5:3000/music"))
        val subpath = "https://example.com/soundscape"
        assertTrue(Server.isOwnPage(subpath, "https://example.com/soundscape"))
        assertTrue(Server.isOwnPage(subpath, "https://example.com/soundscape/settings"))
        assertFalse(Server.isOwnPage(subpath, "https://example.com/soundscape2/settings"))
        assertFalse(Server.isOwnPage(subpath, "https://example.com/other"))
        assertEquals("", Server.basePath(server))
        assertEquals("/soundscape", Server.basePath("$subpath/"))
    }

    @Test
    fun recognisesASoundscapeServer() {
        assertTrue(Server.looksLikeSoundscape("""{"app": "soundscape", "api": 1, "version": "0.1.0"}"""))
        assertTrue(Server.looksLikeSoundscape("""{"app":"soundscape"}"""))
        assertFalse(Server.looksLikeSoundscape("""{"app":"navidrome"}"""))
        assertFalse(Server.looksLikeSoundscape("<html>Hello</html>"))
        assertEquals("https://music.example.com/api/info", Server.infoAddress("https://music.example.com"))
    }
}
