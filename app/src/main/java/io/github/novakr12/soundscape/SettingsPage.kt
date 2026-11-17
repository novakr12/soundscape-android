package io.github.novakr12.soundscape

import org.json.JSONObject

sealed class SettingsCommand {
    data class Option(val name: String, val value: Boolean) : SettingsCommand()
    data object Connect : SettingsCommand()
    data object Disconnect : SettingsCommand()
    data object ChangeServer : SettingsCommand()
    data object NotificationSettings : SettingsCommand()
    data object OpenDownloads : SettingsCommand()
}

data class SettingsAsk(val page: String, val commands: List<SettingsCommand>)

data class AppSettingsView(
    val version: String,
    val connected: Boolean,
    val user: String?,
    val backgroundPlayback: Boolean,
    val notifications: Boolean,
    val notificationsAllowed: Boolean,
    val downloadsWifiOnly: Boolean = false,
    val downloads: String? = null,
)

object SettingsPage {
    const val OPTION_BACKGROUND = "background_playback"
    const val OPTION_NOTIFICATIONS = "notifications"
    const val OPTION_WIFI = "downloads_wifi_only"
    private const val MOST_AT_ONCE = 20

    const val READ = """(() => {
  if (location.pathname !== "/settings") return null;
  window.soundscapeAppPage ??= String(Math.random()).slice(2);
  const queued = Array.isArray(window.soundscapeAppCommands) ? window.soundscapeAppCommands.splice(0, 20) : [];
  return { page: window.soundscapeAppPage, commands: queued };
})()"""

    fun parse(json: String?): SettingsAsk? {
        if (json.isNullOrBlank() || json == "null") return null
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val page = root.optString("page").takeIf { it.isNotEmpty() } ?: return null
        val list = root.optJSONArray("commands")
        val commands = (0 until minOf(list?.length() ?: 0, MOST_AT_ONCE)).mapNotNull { index ->
            val item = list?.optJSONObject(index) ?: return@mapNotNull null
            when (item.optString("do")) {
                "option" -> {
                    val name = item.optString("name")
                    val value = item.opt("value")
                    if ((name == OPTION_BACKGROUND || name == OPTION_NOTIFICATIONS || name == OPTION_WIFI) && value is Boolean) {
                        SettingsCommand.Option(name, value)
                    } else {
                        null
                    }
                }
                "connect" -> SettingsCommand.Connect
                "disconnect" -> SettingsCommand.Disconnect
                "change-server" -> SettingsCommand.ChangeServer
                "notification-settings" -> SettingsCommand.NotificationSettings
                "open-downloads" -> SettingsCommand.OpenDownloads
                else -> null
            }
        }
        return SettingsAsk(page, commands)
    }

    fun payload(view: AppSettingsView, error: String?): String = JSONObject()
        .put(
            "settings",
            JSONObject()
                .put("app", "android")
                .put("version", view.version)
                .put("connected", view.connected)
                .put("user", view.user ?: JSONObject.NULL)
                .put(OPTION_BACKGROUND, view.backgroundPlayback)
                .put(OPTION_NOTIFICATIONS, view.notifications)
                .put("notifications_allowed", view.notificationsAllowed)
                .put(OPTION_WIFI, view.downloadsWifiOnly)
                .put("downloads", view.downloads ?: JSONObject.NULL),
        )
        .put("error", error ?: JSONObject.NULL)
        .toString()

    fun pushScript(payload: String) =
        "(() => { const detail = JSON.parse(${JSONObject.quote(payload)}); window.soundscapeAppSettings = detail; window.dispatchEvent(new CustomEvent(\"soundscape-app-settings\", { detail })); })()"
}
