package io.github.novakr12.soundscape

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat

internal fun Activity.matchBarsToTheme() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}
