package io.github.novakr12.soundscape

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object LauncherIcon {
    private const val GREEN = "MainActivity"

    val presets = linkedMapOf(
        GREEN to 0x1DB954,
        "IconTeal" to 0x14B8A6,
        "IconBlue" to 0x3B82F6,
        "IconPurple" to 0x8B5CF6,
        "IconPink" to 0xEC4899,
        "IconRed" to 0xEF4444,
        "IconOrange" to 0xF97316,
        "IconYellow" to 0xEAB308,
    )

    fun nearest(accent: String?): String {
        val color = accent?.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) }?.substring(1)?.toInt(16) ?: return GREEN
        return presets.minBy { (_, preset) ->
            listOf(16, 8, 0).sumOf { shift ->
                val difference = (color shr shift and 0xFF) - (preset shr shift and 0xFF)
                difference * difference
            }
        }.key
    }

    fun show(context: Context, accent: String?) {
        val wanted = nearest(accent)
        val packages = context.packageManager
        val prefix = PlayerActivity::class.java.name.substringBeforeLast('.')
        val component = { name: String -> ComponentName(context.packageName, "$prefix.$name") }
        val shown = { name: String ->
            when (packages.getComponentEnabledSetting(component(name))) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == GREEN
                else -> false
            }
        }
        if (shown(wanted) && presets.keys.none { it != wanted && shown(it) }) return
        val states = presets.keys.sortedBy { it != wanted }.associateWith { name ->
            if (name == wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for ((name, state) in states) {
            packages.setComponentEnabledSetting(component(name), state, PackageManager.DONT_KILL_APP)
        }
    }
}
