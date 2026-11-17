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

    var askedToConnect: Boolean
        get() = stored.getBoolean(ASKED, false)
        set(value) = stored.edit { putBoolean(ASKED, value) }

    fun forgetConnection() = stored.edit {
        remove(TOKEN)
        remove(USER)
        remove(ASKED)
    }

    private companion object {
        const val SERVER = "server"
        const val TOKEN = "token"
        const val USER = "user"
        const val ASKED = "asked_to_connect"
    }
}
