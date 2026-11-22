package io.github.novakr12.soundscape

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import java.io.File

class SoundscapeApp : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND && isPlaybackProcess(this)) {
            Playback.service?.clearLibrary()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (isPlaybackProcess(this)) {
            Playback.service?.clearLibrary()
        }
    }

    companion object {
        fun processName(context: Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return Application.getProcessName()
            }
            val cmdline = runCatching {
                File("/proc/self/cmdline").readText().trim('\u0000')
            }.getOrNull()
            return cmdline?.takeIf { it.isNotEmpty() } ?: context.packageName
        }

        fun isPlaybackProcess(context: Context): Boolean = processName(context).endsWith(":playback")
    }
}
