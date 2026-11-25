package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepClockTest {
    @Test
    fun reportsAfterSongAndFiresOnceWhenSongFinishes() {
        val clock = SleepClock()
        assertNull(clock.snapshot(1000L))
        assertFalse(clock.isAfterSong)

        clock.set(SleepMode.AfterSong, 1000L)
        assertTrue(clock.isAfterSong)
        assertEquals(SleepSnapshot.AfterSong, clock.snapshot(2000L))
        assertNull(clock.remainingMs(2000L))

        assertTrue(clock.onSongFinished())
        assertFalse(clock.isAfterSong)
        assertNull(clock.snapshot(2000L))
        assertFalse(clock.onSongFinished())
    }

    @Test
    fun fadesOutOverTenSecondsAndResetsCleanly() {
        val clock = SleepClock()
        clock.set(SleepMode.Seconds(90.0), 1_000L)
        assertEquals(90_000L, clock.remainingMs(1_000L))
        assertEquals(SleepSnapshot.Remaining(30.0), clock.snapshot(61_000L))
        assertEquals(SleepSnapshot.Remaining(0.0), clock.snapshot(95_000L))

        val beforeDeadline = clock.step(90_999L, playing = true)
        assertFalse(beforeDeadline.changed)
        assertFalse(beforeDeadline.fading)
        assertFalse(beforeDeadline.shouldPause)
        assertEquals(1f, beforeDeadline.fadeGain, 0.0001f)

        val trigger = clock.step(91_000L, playing = true)
        assertTrue(trigger.changed)
        assertTrue(trigger.fading)
        assertFalse(trigger.shouldPause)
        assertNull(clock.snapshot(91_000L))
        assertTrue(clock.isFading)

        val mid = clock.step(96_000L, playing = true)
        assertFalse(mid.changed)
        assertTrue(mid.fading)
        assertFalse(mid.shouldPause)
        assertEquals(0.5f, mid.fadeGain, 0.001f)

        val end = clock.step(101_000L, playing = true)
        assertTrue(end.changed)
        assertFalse(end.fading)
        assertTrue(end.shouldPause)
        assertEquals(0f, end.fadeGain, 0.0001f)
        assertFalse(clock.isFading)

        assertTrue(clock.cancelFade())
        assertEquals(1f, clock.fadeGain, 0.0001f)
        assertFalse(clock.cancelFade())
    }

    @Test
    fun clearsTimerWhenPausedAtDeadlineAndCancelsFadeOnReset() {
        val clock = SleepClock()
        clock.set(SleepMode.Seconds(10.0), 1000L)
        val idleDeadline = clock.step(11_000L, playing = false)
        assertTrue(idleDeadline.changed)
        assertFalse(idleDeadline.fading)
        assertFalse(idleDeadline.shouldPause)
        assertEquals(1f, idleDeadline.fadeGain, 0f)
        assertNull(clock.remainingMs(11_000L))

        clock.set(SleepMode.Seconds(5.0), 20_000L)
        clock.step(25_000L, playing = true)
        clock.step(30_000L, playing = true)
        assertEquals(0.5f, clock.fadeGain, 0.001f)

        clock.set(SleepMode.Off, 30_000L)
        assertFalse(clock.isFading)
        assertEquals(1f, clock.fadeGain, 0f)
        assertNull(clock.snapshot(30_000L))
    }
}
