package io.github.novakr12.soundscape

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPageTest {
    @Test
    fun takesOnlyTheCommandsItKnows() {
        val ask = SettingsPage.parse(
            """{"page":"123","commands":[
                {"do":"option","name":"background_playback","value":false},
                {"do":"option","name":"notifications","value":true},
                {"do":"option","name":"scrobbling","value":true},
                {"do":"option","name":"notifications","value":"yes"},
                {"do":"connect"},{"do":"disconnect"},{"do":"change-server"},{"do":"notification-settings"},
                {"do":"sign-out"},"connect"
            ]}""",
        )!!
        assertEquals("123", ask.page)
        assertEquals(
            listOf(
                SettingsCommand.Option("background_playback", false),
                SettingsCommand.Option("notifications", true),
                SettingsCommand.Connect,
                SettingsCommand.Disconnect,
                SettingsCommand.ChangeServer,
                SettingsCommand.NotificationSettings,
            ),
            ask.commands,
        )
    }

    @Test
    fun ignoresOtherPages() {
        assertNull(SettingsPage.parse("null"))
        assertNull(SettingsPage.parse(null))
        assertNull(SettingsPage.parse("""{"commands":[]}"""))
        assertTrue(SettingsPage.parse("""{"page":"1"}""")!!.commands.isEmpty())
    }

    @Test
    fun takesAtMostTwentyAtOnce() {
        val many = (1..50).joinToString(",") { """{"do":"connect"}""" }
        assertEquals(20, SettingsPage.parse("""{"page":"1","commands":[$many]}""")!!.commands.size)
    }

    @Test
    fun describesItselfAsTheAndroidApp() {
        val payload = JSONObject(
            SettingsPage.payload(
                AppSettingsView("0.4.0", true, "ana", true, false, true),
                null,
            ),
        )
        val settings = payload.getJSONObject("settings")
        assertEquals("android", settings.getString("app"))
        assertEquals("0.4.0", settings.getString("version"))
        assertEquals("ana", settings.getString("user"))
        assertTrue(settings.getBoolean("background_playback"))
        assertFalse(settings.getBoolean("notifications"))
        assertTrue(payload.isNull("error"))
        assertTrue(JSONObject(SettingsPage.payload(AppSettingsView("1", false, null, true, true, true), "x")).getJSONObject("settings").isNull("user"))
    }

    @Test
    fun handsThePageItsSettingsSafely() {
        val script = SettingsPage.pushScript("""{"settings":{"user":"</script>\"'`"},"error":null}""")
        assertTrue(script.startsWith("(() => { const detail = JSON.parse(\""))
        assertTrue(script.contains("soundscape-app-settings"))
        assertFalse(script.contains("</script>"))
    }
}
