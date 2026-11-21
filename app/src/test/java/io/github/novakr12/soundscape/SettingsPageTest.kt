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
                {"do":"option","name":"downloads_wifi_only","value":true},
                {"do":"option","name":"streak_reminder","value":true},
                {"do":"option","name":"discord","value":true},
                {"do":"discord-app","id":"1234567890123456789"},
                {"do":"discord-app","id":123},
                {"do":"option","name":"scrobbling","value":true},
                {"do":"option","name":"notifications","value":"yes"},
                {"do":"connect"},{"do":"disconnect"},{"do":"change-server"},{"do":"notification-settings"},{"do":"open-downloads"},{"do":"download-update"},
                {"do":"sign-out"},"connect"
            ]}""",
        )!!
        assertEquals("123", ask.page)
        assertEquals(
            listOf(
                SettingsCommand.Option("background_playback", false),
                SettingsCommand.Option("notifications", true),
                SettingsCommand.Option("downloads_wifi_only", true),
                SettingsCommand.Option("streak_reminder", true),
                SettingsCommand.Option("discord", true),
                SettingsCommand.DiscordApp("1234567890123456789"),
                SettingsCommand.Connect,
                SettingsCommand.Disconnect,
                SettingsCommand.ChangeServer,
                SettingsCommand.NotificationSettings,
                SettingsCommand.OpenDownloads,
                SettingsCommand.DownloadUpdate,
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
                AppSettingsView(
                    "0.4.0",
                    true,
                    "ana",
                    true,
                    false,
                    true,
                    true,
                    "3 songs · 20 MB",
                    streakReminder = true,
                    update = "0.5.0",
                    discord = true,
                    discordAppId = "1234567890123456789",
                ),
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
        assertTrue(settings.getBoolean("downloads_wifi_only"))
        assertEquals("3 songs · 20 MB", settings.getString("downloads"))
        assertTrue(settings.getBoolean("weekly_recap"))
        assertTrue(settings.getBoolean("streak_reminder"))
        assertTrue(settings.getBoolean("update_notice"))
        assertEquals("0.5.0", settings.getString("update"))
        assertTrue(settings.getBoolean("discord"))
        assertEquals("1234567890123456789", settings.getString("discord_app_id"))
        val minimal = JSONObject(SettingsPage.payload(AppSettingsView("1", false, null, true, true, true), "x"))
            .getJSONObject("settings")
        assertTrue(minimal.isNull("user"))
        assertFalse(minimal.getBoolean("discord"))
        assertTrue(minimal.isNull("discord_app_id"))
    }

    @Test
    fun handsThePageItsSettingsSafely() {
        val script = SettingsPage.pushScript("""{"settings":{"user":"</script>\"'`"},"error":null}""")
        assertTrue(script.startsWith("(() => { const detail = JSON.parse(\""))
        assertTrue(script.contains("soundscape-app-settings"))
        assertFalse(script.contains("</script>"))
    }

    @Test
    fun matchesSettingsPathForRootAndSubpathServers() {
        assertTrue(SettingsPage.read("https://music.example.com").contains("location.pathname !== \"/settings\""))
        assertTrue(
            SettingsPage.read("https://example.com/soundscape").contains("location.pathname !== \"/soundscape/settings\""),
        )
    }
}
