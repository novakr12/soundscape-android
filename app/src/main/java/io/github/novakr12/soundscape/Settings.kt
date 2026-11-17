package io.github.novakr12.soundscape

import android.content.Context
import androidx.core.content.edit

class Settings(context: Context) {
    private val stored = context.getSharedPreferences("soundscape", Context.MODE_PRIVATE)

    var server: String?
        get() = stored.getString(SERVER, null)
        set(value) = stored.edit { putString(SERVER, value) }

    private companion object {
        const val SERVER = "server"
    }
}
