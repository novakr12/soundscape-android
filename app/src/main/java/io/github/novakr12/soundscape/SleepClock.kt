package io.github.novakr12.soundscape

import kotlin.math.roundToLong

sealed class SleepMode {
    data object Off : SleepMode()
    data object AfterSong : SleepMode()
    data class Seconds(val seconds: Double) : SleepMode()
}

sealed class SleepSnapshot {
    data object AfterSong : SleepSnapshot()
    data class Remaining(val left: Double) : SleepSnapshot()
}

data class SleepStep(
    val changed: Boolean,
    val fadeGain: Float,
    val shouldPause: Boolean,
    val fading: Boolean,
)

class SleepClock {
    private var afterSong = false
    private var deadlineMs: Long? = null
    private var fadeStartedMs: Long? = null
    private var fadeFrom = 1.0

    var fadeGain: Float = 1f
        private set

    val isAfterSong: Boolean
        get() = afterSong

    val isFading: Boolean
        get() = fadeStartedMs != null

    fun set(mode: SleepMode, nowMs: Long) {
        fadeStartedMs = null
        fadeFrom = 1.0
        fadeGain = 1f
        when (mode) {
            SleepMode.Off -> {
                afterSong = false
                deadlineMs = null
            }
            SleepMode.AfterSong -> {
                afterSong = true
                deadlineMs = null
            }
            is SleepMode.Seconds -> {
                afterSong = false
                val bounded = if (mode.seconds.isFinite()) {
                    mode.seconds.coerceIn(1.0, MAX_SECONDS)
                } else {
                    1.0
                }
                deadlineMs = nowMs + (bounded * 1000.0).roundToLong()
            }
        }
    }

    fun snapshot(nowMs: Long): SleepSnapshot? = when {
        afterSong -> SleepSnapshot.AfterSong
        deadlineMs != null -> SleepSnapshot.Remaining(((deadlineMs!! - nowMs).coerceAtLeast(0L)) / 1000.0)
        else -> null
    }

    fun remainingMs(nowMs: Long): Long? = deadlineMs?.let { (it - nowMs).coerceAtLeast(0L) }

    fun step(nowMs: Long, playing: Boolean): SleepStep {
        val deadline = deadlineMs
        if (deadline != null && nowMs >= deadline) {
            deadlineMs = null
            if (playing) {
                fadeStartedMs = nowMs
                fadeFrom = fadeGain.toDouble()
                return SleepStep(changed = true, fadeGain = fadeGain, shouldPause = false, fading = true)
            }
            return SleepStep(changed = true, fadeGain = 1f, shouldPause = false, fading = false)
        }
        val started = fadeStartedMs ?: return SleepStep(changed = false, fadeGain = fadeGain, shouldPause = false, fading = false)
        val done = ((nowMs - started).toDouble() / FADE_MS).coerceIn(0.0, 1.0)
        if (done >= 1.0) {
            fadeStartedMs = null
            fadeFrom = 1.0
            fadeGain = 0f
            return SleepStep(changed = true, fadeGain = 0f, shouldPause = true, fading = false)
        }
        fadeGain = (fadeFrom * (1.0 - done)).toFloat()
        return SleepStep(changed = false, fadeGain = fadeGain, shouldPause = false, fading = true)
    }

    fun onSongFinished(): Boolean {
        if (!afterSong) return false
        afterSong = false
        return true
    }

    fun cancelFade(): Boolean {
        if (fadeStartedMs == null && fadeGain == 1f) return false
        fadeStartedMs = null
        fadeFrom = 1.0
        fadeGain = 1f
        return true
    }

    fun clear() {
        afterSong = false
        deadlineMs = null
        fadeStartedMs = null
        fadeFrom = 1.0
        fadeGain = 1f
    }

    companion object {
        const val FADE_MS = 10_000L
        const val FADE_STEP_MS = 50L
        const val MAX_SECONDS = 86_400.0
    }
}
