package io.github.novakr12.soundscape

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherIconTest {
    @Test
    fun picksTheNearestPresetIcon() {
        assertEquals("MainActivity", LauncherIcon.nearest(null))
        assertEquals("MainActivity", LauncherIcon.nearest("green"))
        assertEquals("MainActivity", LauncherIcon.nearest("#1db954"))
        assertEquals("IconPurple", LauncherIcon.nearest("#8B5CF6"))
        assertEquals("IconBlue", LauncherIcon.nearest("#2563eb"))
        assertEquals("IconRed", LauncherIcon.nearest("#cc0000"))
        assertEquals("IconYellow", LauncherIcon.nearest("#ffd700"))
    }
}
