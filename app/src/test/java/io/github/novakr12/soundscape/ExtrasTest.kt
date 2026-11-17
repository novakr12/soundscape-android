package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasTest {
    private val week = """{"show":true,"recap":{"from":"2026-11-09","plays":42,"listeningMs":7500000,"topArtist":{"name":"Low"},"place":{"rank":2,"of":5}}}"""

    @Test
    fun readsTheWeeklyRecap() {
        val (show, recap) = Extras.recap(week)!!
        assertTrue(show)
        assertEquals("2026-11-09", recap!!.from)
        assertEquals("42 plays · 2 h 5 min · most played: Low · #2 of 5 among friends", Extras.recapBody(recap))
    }

    @Test
    fun showsEachRecapOnce() {
        val (show, recap) = Extras.recap(week)!!
        assertTrue(Extras.recapDue(show, recap, null))
        assertFalse(Extras.recapDue(show, recap, "2026-11-09"))
        assertFalse(Extras.recapDue(false, recap, null))
        assertNull(Extras.recap("not json"))
        assertEquals(false to null, Extras.recap("""{"show":false}"""))
    }

    @Test
    fun remindsOnlyLateWhenTheStreakIsAtRisk() {
        val today = Extras.today("""{"plays":0,"listeningMs":0,"streak":4,"playedToday":false}""")!!
        assertTrue(Extras.remind(today, 21, "2026-11-17", true, null))
        assertFalse(Extras.remind(today, 20, "2026-11-17", true, null))
        assertFalse(Extras.remind(today, 22, "2026-11-17", true, "2026-11-17"))
        assertFalse(Extras.remind(today, 22, "2026-11-17", false, null))
        assertFalse(Extras.remind(today.copy(playedToday = true), 22, "2026-11-17", true, null))
        assertFalse(Extras.remind(today.copy(streak = 0), 22, "2026-11-17", true, null))
        assertEquals("Your 4-day streak ends at midnight", Extras.reminderTitle(today))
        assertNull(Extras.today("{}"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(Extras.newer("0.6.0", "0.6.1"))
        assertTrue(Extras.newer("0.6.0", "v0.10.0"))
        assertTrue(Extras.newer("0.6", "0.6.1"))
        assertFalse(Extras.newer("0.6.0", "0.6.0"))
        assertFalse(Extras.newer("0.6.0", "0.5.9"))
        assertFalse(Extras.newer("0.6.0", "nightly"))
    }

    @Test
    fun readsTheLatestRelease() {
        assertEquals("0.7.0", Extras.releaseVersion("""{"tag_name":"v0.7.0"}"""))
        assertNull(Extras.releaseVersion("""{"tag_name":"v0.7.0","prerelease":true}"""))
        assertNull(Extras.releaseVersion("""{"tag_name":"latest"}"""))
        assertTrue(Extras.shouldTell("0.7.0", true, null))
        assertFalse(Extras.shouldTell("0.7.0", true, "0.7.0"))
        assertFalse(Extras.shouldTell("0.7.0", false, null))
    }
}
