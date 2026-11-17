package io.github.novakr12.soundscape

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val stored = context.getSharedPreferences("soundscape", Context.MODE_PRIVATE)

    var server: String?
        get() = stored.getString(SERVER, null)
        set(value) = stored.edit { putString(SERVER, value) }

    var token: String?
        get() = stored.getString(TOKEN, null)
        set(value) = stored.edit { putString(TOKEN, value) }

    var user: String?
        get() = stored.getString(USER, null)
        set(value) = stored.edit { putString(USER, value) }

    var backgroundPlayback: Boolean
        get() = stored.getBoolean(BACKGROUND, true)
        set(value) = stored.edit { putBoolean(BACKGROUND, value) }

    var downloadsWifiOnly: Boolean
        get() = stored.getBoolean(WIFI_ONLY, false)
        set(value) = stored.edit { putBoolean(WIFI_ONLY, value) }

    var notifications: Boolean
        get() = stored.getBoolean(NOTIFICATIONS, true)
        set(value) = stored.edit { putBoolean(NOTIFICATIONS, value) }

    var lastNotification: Long?
        get() = if (stored.contains(LAST_NOTIFICATION)) stored.getLong(LAST_NOTIFICATION, 0) else null
        set(value) = stored.edit { if (value == null) remove(LAST_NOTIFICATION) else putLong(LAST_NOTIFICATION, value) }

    var askedForNotifications: Boolean
        get() = stored.getBoolean(ASKED_NOTIFICATIONS, false)
        set(value) = stored.edit { putBoolean(ASKED_NOTIFICATIONS, value) }

    var askedToConnect: Boolean
        get() = stored.getBoolean(ASKED, false)
        set(value) = stored.edit { putBoolean(ASKED, value) }

    fun forgetConnection() = stored.edit {
        remove(TOKEN)
        remove(USER)
        remove(ASKED)
        remove(LAST_NOTIFICATION)
    }

    private companion object {
        const val SERVER = "server"
        const val TOKEN = "token"
        const val USER = "user"
        const val ASKED = "asked_to_connect"
        const val NOTIFICATIONS = "notifications"
        const val BACKGROUND = "background_playback"
        const val WIFI_ONLY = "downloads_wifi_only"
        const val LAST_NOTIFICATION = "last_notification"
        const val ASKED_NOTIFICATIONS = "asked_for_notifications"
    }
}
