package io.github.novakr12.soundscape

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.core.content.edit

class Settings(context: Context) {
    private val app = context.applicationContext
    private val local = SoundscapeApp.isPlaybackProcess(app)
    private val stored: SharedPreferences by lazy { app.getSharedPreferences("soundscape", Context.MODE_PRIVATE) }

    var server: String?
        get() = getString(SERVER)
        set(value) = putString(SERVER, value)

    var token: String?
        get() = getString(TOKEN)
        set(value) = putString(TOKEN, value)

    var user: String?
        get() = getString(USER)
        set(value) = putString(USER, value)

    var autoplay: Boolean
        get() = getBoolean(AUTOPLAY, false)
        set(value) = putBoolean(AUTOPLAY, value)

    var shuffle: Boolean
        get() = getBoolean(SHUFFLE, false)
        set(value) = putBoolean(SHUFFLE, value)

    var backgroundPlayback: Boolean
        get() = getBoolean(BACKGROUND, true)
        set(value) = putBoolean(BACKGROUND, value)

    val deviceId: String
        get() {
            if (!local) {
                AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_DEVICE_ID)
                    ?.getString(AppBridgeProvider.KEY_VALUE)
                    ?.takeIf { Remote.isDeviceId(it) }
                    ?.let { return it }
            }
            return stored.getString(DEVICE_ID, null)?.takeIf { Remote.isDeviceId(it) }
                ?: Remote.newDeviceId().also { made -> stored.edit { putString(DEVICE_ID, made) } }
        }

    var weeklyRecap: Boolean
        get() = getBoolean(WEEKLY_RECAP, true)
        set(value) = putBoolean(WEEKLY_RECAP, value)

    var streakReminder: Boolean
        get() = getBoolean(STREAK_REMINDER, false)
        set(value) = putBoolean(STREAK_REMINDER, value)

    var updateNotice: Boolean
        get() = getBoolean(UPDATE_NOTICE, true)
        set(value) = putBoolean(UPDATE_NOTICE, value)

    var discord: Boolean
        get() = getBoolean(DISCORD, false)
        set(value) = putBoolean(DISCORD, value)

    var discordAppId: String?
        get() = getString(DISCORD_APP_ID)
        set(value) = putString(DISCORD_APP_ID, value)

    var lastRecap: String?
        get() = getString(LAST_RECAP)
        set(value) = putString(LAST_RECAP, value)

    var lastReminder: String?
        get() = getString(LAST_REMINDER)
        set(value) = putString(LAST_REMINDER, value)

    var toldUpdate: String?
        get() = getString(TOLD_UPDATE)
        set(value) = putString(TOLD_UPDATE, value)

    var updateAvailable: String?
        get() = getString(UPDATE_AVAILABLE)
        set(value) = putString(UPDATE_AVAILABLE, value)

    var updateCheckedAt: Long
        get() = getLong(UPDATE_CHECKED_AT, 0L)
        set(value) = putLong(UPDATE_CHECKED_AT, value)

    var downloadsWifiOnly: Boolean
        get() = getBoolean(WIFI_ONLY, false)
        set(value) = putBoolean(WIFI_ONLY, value)

    var notifications: Boolean
        get() = getBoolean(NOTIFICATIONS, true)
        set(value) = putBoolean(NOTIFICATIONS, value)

    var lastNotification: Long?
        get() = getNullableLong(LAST_NOTIFICATION)
        set(value) = putNullableLong(LAST_NOTIFICATION, value)

    var askedForNotifications: Boolean
        get() = getBoolean(ASKED_NOTIFICATIONS, false)
        set(value) = putBoolean(ASKED_NOTIFICATIONS, value)

    var askedToConnect: Boolean
        get() = getBoolean(ASKED, false)
        set(value) = putBoolean(ASKED, value)

    fun forgetConnection() {
        if (!local && AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_FORGET_CONNECTION) != null) return
        stored.edit {
            remove(TOKEN)
            remove(USER)
            remove(ASKED)
            remove(LAST_NOTIFICATION)
        }
    }

    internal fun getRawString(key: String): String? = stored.getString(key, null)

    internal fun putRawString(key: String, value: String?) {
        stored.edit { if (value == null) remove(key) else putString(key, value) }
    }

    internal fun getRawBoolean(key: String, default: Boolean): Boolean = stored.getBoolean(key, default)

    internal fun putRawBoolean(key: String, value: Boolean) {
        stored.edit { putBoolean(key, value) }
    }

    internal fun hasRawKey(key: String): Boolean = stored.contains(key)

    internal fun getRawLong(key: String, default: Long): Long = stored.getLong(key, default)

    internal fun putRawLong(key: String, value: Long?) {
        stored.edit { if (value == null) remove(key) else putLong(key, value) }
    }

    private fun getString(key: String): String? {
        if (!local) {
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_GET_STRING, key)?.let { bundle ->
                return bundle.getString(AppBridgeProvider.KEY_VALUE)
            }
        }
        return stored.getString(key, null)
    }

    private fun putString(key: String, value: String?) {
        if (!local) {
            val args = Bundle().apply {
                if (value == null) {
                    putBoolean(AppBridgeProvider.KEY_REMOVE, true)
                } else {
                    putBoolean(AppBridgeProvider.KEY_REMOVE, false)
                    putString(AppBridgeProvider.KEY_VALUE, value)
                }
            }
            if (AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_PUT_STRING, key, args) != null) return
        }
        stored.edit { if (value == null) remove(key) else putString(key, value) }
    }

    private fun getBoolean(key: String, default: Boolean): Boolean {
        if (!local) {
            val args = Bundle().apply { putBoolean(AppBridgeProvider.KEY_DEFAULT, default) }
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_GET_BOOLEAN, key, args)?.let { bundle ->
                return bundle.getBoolean(AppBridgeProvider.KEY_VALUE, default)
            }
        }
        return stored.getBoolean(key, default)
    }

    private fun putBoolean(key: String, value: Boolean) {
        if (!local) {
            val args = Bundle().apply { putBoolean(AppBridgeProvider.KEY_VALUE, value) }
            if (AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_PUT_BOOLEAN, key, args) != null) return
        }
        stored.edit { putBoolean(key, value) }
    }

    private fun getLong(key: String, default: Long): Long {
        if (!local) {
            val args = Bundle().apply { putLong(AppBridgeProvider.KEY_DEFAULT, default) }
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_GET_LONG, key, args)?.let { bundle ->
                return bundle.getLong(AppBridgeProvider.KEY_VALUE, default)
            }
        }
        return stored.getLong(key, default)
    }

    private fun putLong(key: String, value: Long) {
        if (!local) {
            val args = Bundle().apply {
                putBoolean(AppBridgeProvider.KEY_REMOVE, false)
                putLong(AppBridgeProvider.KEY_VALUE, value)
            }
            if (AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_PUT_LONG, key, args) != null) return
        }
        stored.edit { putLong(key, value) }
    }

    private fun getNullableLong(key: String): Long? {
        if (!local) {
            val args = Bundle().apply { putLong(AppBridgeProvider.KEY_DEFAULT, 0L) }
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_GET_LONG, key, args)?.let { bundle ->
                return if (bundle.getBoolean(AppBridgeProvider.KEY_PRESENT, false)) {
                    bundle.getLong(AppBridgeProvider.KEY_VALUE, 0L)
                } else {
                    null
                }
            }
        }
        return if (stored.contains(key)) stored.getLong(key, 0L) else null
    }

    private fun putNullableLong(key: String, value: Long?) {
        if (!local) {
            val args = Bundle().apply {
                if (value == null) {
                    putBoolean(AppBridgeProvider.KEY_REMOVE, true)
                } else {
                    putBoolean(AppBridgeProvider.KEY_REMOVE, false)
                    putLong(AppBridgeProvider.KEY_VALUE, value)
                }
            }
            if (AppBridgeProvider.call(app, AppBridgeProvider.METHOD_PREF_PUT_LONG, key, args) != null) return
        }
        stored.edit { if (value == null) remove(key) else putLong(key, value) }
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
        const val DISCORD = "discord"
        const val DISCORD_APP_ID = "discord_app_id"
        const val LAST_RECAP = "last_recap"
        const val LAST_REMINDER = "last_reminder"
        const val TOLD_UPDATE = "told_update"
        const val UPDATE_AVAILABLE = "update_available"
        const val UPDATE_CHECKED_AT = "update_checked_at"
        const val LAST_NOTIFICATION = "last_notification"
        const val ASKED_NOTIFICATIONS = "asked_for_notifications"
    }
}
