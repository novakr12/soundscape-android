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

    var autoplay: Boolean
        get() = stored.getBoolean(AUTOPLAY, false)
        set(value) = stored.edit { putBoolean(AUTOPLAY, value) }

    var shuffle: Boolean
        get() = stored.getBoolean(SHUFFLE, false)
        set(value) = stored.edit { putBoolean(SHUFFLE, value) }

    var backgroundPlayback: Boolean
        get() = stored.getBoolean(BACKGROUND, true)
        set(value) = stored.edit { putBoolean(BACKGROUND, value) }

    val deviceId: String
        get() = stored.getString(DEVICE_ID, null)?.takeIf { Remote.isDeviceId(it) }
            ?: Remote.newDeviceId().also { made -> stored.edit { putString(DEVICE_ID, made) } }

    var weeklyRecap: Boolean
        get() = stored.getBoolean(WEEKLY_RECAP, true)
        set(value) = stored.edit { putBoolean(WEEKLY_RECAP, value) }

    var streakReminder: Boolean
        get() = stored.getBoolean(STREAK_REMINDER, false)
        set(value) = stored.edit { putBoolean(STREAK_REMINDER, value) }

    var updateNotice: Boolean
        get() = stored.getBoolean(UPDATE_NOTICE, true)
        set(value) = stored.edit { putBoolean(UPDATE_NOTICE, value) }

    var lastRecap: String?
        get() = stored.getString(LAST_RECAP, null)
        set(value) = stored.edit { putString(LAST_RECAP, value) }

    var lastReminder: String?
        get() = stored.getString(LAST_REMINDER, null)
        set(value) = stored.edit { putString(LAST_REMINDER, value) }

    var toldUpdate: String?
        get() = stored.getString(TOLD_UPDATE, null)
        set(value) = stored.edit { putString(TOLD_UPDATE, value) }

    var updateAvailable: String?
        get() = stored.getString(UPDATE_AVAILABLE, null)
        set(value) = stored.edit { putString(UPDATE_AVAILABLE, value) }

    var updateCheckedAt: Long
        get() = stored.getLong(UPDATE_CHECKED_AT, 0)
        set(value) = stored.edit { putLong(UPDATE_CHECKED_AT, value) }

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
        const val AUTOPLAY = "autoplay"
        const val SHUFFLE = "shuffle"
        const val WIFI_ONLY = "downloads_wifi_only"
        const val DEVICE_ID = "device_id"
        const val WEEKLY_RECAP = "weekly_recap"
        const val STREAK_REMINDER = "streak_reminder"
        const val UPDATE_NOTICE = "update_notice"
        const val LAST_RECAP = "last_recap"
        const val LAST_REMINDER = "last_reminder"
        const val TOLD_UPDATE = "told_update"
        const val UPDATE_AVAILABLE = "update_available"
        const val UPDATE_CHECKED_AT = "update_checked_at"
        const val LAST_NOTIFICATION = "last_notification"
        const val ASKED_NOTIFICATIONS = "asked_for_notifications"
    }
}
