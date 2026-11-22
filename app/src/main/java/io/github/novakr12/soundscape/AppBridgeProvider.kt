package io.github.novakr12.soundscape

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppBridgeProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())

    data class PlaybackStatus(
        val running: Boolean,
        val playingNatively: Boolean,
        val playingForPage: Boolean,
        val handingBack: Boolean,
    )

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val app = context?.applicationContext ?: return null
        return when (method) {
            METHOD_PREF_GET_STRING -> {
                val key = arg ?: return null
                Bundle().apply { putString(KEY_VALUE, Settings(app).getRawString(key)) }
            }
            METHOD_PREF_PUT_STRING -> {
                val key = arg ?: return null
                val remove = extras?.getBoolean(KEY_REMOVE, false) ?: false
                Settings(app).putRawString(key, if (remove) null else extras?.getString(KEY_VALUE))
                Bundle()
            }
            METHOD_PREF_GET_BOOLEAN -> {
                val key = arg ?: return null
                val default = extras?.getBoolean(KEY_DEFAULT, false) ?: false
                Bundle().apply { putBoolean(KEY_VALUE, Settings(app).getRawBoolean(key, default)) }
            }
            METHOD_PREF_PUT_BOOLEAN -> {
                val key = arg ?: return null
                val value = extras?.getBoolean(KEY_VALUE, false) ?: false
                Settings(app).putRawBoolean(key, value)
                Bundle()
            }
            METHOD_PREF_GET_LONG -> {
                val key = arg ?: return null
                val settings = Settings(app)
                val present = settings.hasRawKey(key)
                val default = extras?.getLong(KEY_DEFAULT, 0L) ?: 0L
                Bundle().apply {
                    putBoolean(KEY_PRESENT, present)
                    putLong(KEY_VALUE, if (present) settings.getRawLong(key, default) else default)
                }
            }
            METHOD_PREF_PUT_LONG -> {
                val key = arg ?: return null
                val remove = extras?.getBoolean(KEY_REMOVE, false) ?: false
                Settings(app).putRawLong(key, if (remove) null else extras?.getLong(KEY_VALUE, 0L))
                Bundle()
            }
            METHOD_PREF_DEVICE_ID -> Bundle().apply { putString(KEY_VALUE, Settings(app).deviceId) }
            METHOD_PREF_FORGET_CONNECTION -> {
                Settings(app).forgetConnection()
                Bundle()
            }

            METHOD_DOWNLOADS_ADD -> {
                val server = extras?.getString(KEY_SERVER) ?: return Bundle()
                val tracks = PlaybackCodecs.decodeTracks(extras.getString(KEY_TRACKS_JSON))
                if (tracks.isNotEmpty()) {
                    onMainOrNull { Downloads.add(app, server, tracks) } ?: return null
                }
                Bundle()
            }
            METHOD_DOWNLOADS_REMOVE -> {
                val ids = extras?.getStringArrayList(KEY_TRACK_IDS) ?: return Bundle()
                if (ids.isNotEmpty()) {
                    onMainOrNull { Downloads.remove(app, ids) } ?: return null
                }
                Bundle()
            }
            METHOD_DOWNLOADS_REMOVE_ALL -> {
                onMainOrNull { Downloads.removeAll(app) } ?: return null
                Bundle()
            }
            METHOD_DOWNLOADS_WIFI_ONLY -> {
                val wifiOnly = extras?.getBoolean(KEY_WIFI_ONLY, false) ?: false
                onMainOrNull { Downloads.setWifiOnly(app, wifiOnly) } ?: return null
                Bundle()
            }
            METHOD_DOWNLOADS_STATES -> {
                val sinceVersion = extras?.getLong(KEY_SINCE_VERSION, -1L) ?: -1L
                val current = onDownloadsReady(app) { Downloads.currentVersion(app) } ?: return null
                if (sinceVersion >= 0L && sinceVersion == current) {
                    return Bundle().apply {
                        putBoolean(KEY_UNCHANGED, true)
                        putLong(KEY_VERSION, current)
                    }
                }
                val (states, latest) = onDownloadsReady(app) { Downloads.statesWithVersion(app) } ?: return null
                Bundle().apply {
                    putBoolean(KEY_UNCHANGED, false)
                    putLong(KEY_VERSION, latest)
                    putString(KEY_JSON, PlaybackCodecs.encodeStringMap(states))
                }
            }
            METHOD_DOWNLOADS_SUMMARY -> {
                val sinceVersion = extras?.getLong(KEY_SINCE_VERSION, -1L) ?: -1L
                val current = onDownloadsReady(app) { Downloads.currentVersion(app) } ?: return null
                if (sinceVersion >= 0L && sinceVersion == current) {
                    return Bundle().apply {
                        putBoolean(KEY_UNCHANGED, true)
                        putLong(KEY_VERSION, current)
                    }
                }
                val (summary, latest) = onDownloadsReady(app) { Downloads.summaryWithVersion(app) } ?: return null
                Bundle().apply {
                    putBoolean(KEY_UNCHANGED, false)
                    putLong(KEY_VERSION, latest)
                    putString(KEY_SUMMARY, summary)
                }
            }
            METHOD_DOWNLOADS_STORED -> {
                val sinceVersion = extras?.getLong(KEY_SINCE_VERSION, -1L) ?: -1L
                val current = onDownloadsReady(app) { Downloads.currentVersion(app) } ?: return null
                if (sinceVersion >= 0L && sinceVersion == current) {
                    return Bundle().apply {
                        putBoolean(KEY_UNCHANGED, true)
                        putLong(KEY_VERSION, current)
                    }
                }
                val (items, latest) = onDownloadsReady(app) { Downloads.storedWithVersion(app) } ?: return null
                Bundle().apply {
                    putBoolean(KEY_UNCHANGED, false)
                    putLong(KEY_VERSION, latest)
                    putString(KEY_JSON, PlaybackCodecs.encodeStoredList(items))
                }
            }

            METHOD_OFFLINE_PLAYS_SEND -> {
                OfflinePlays.send(app)
                Bundle()
            }

            METHOD_PLAYBACK_STATUS -> {
                val service = Playback.service
                Bundle().apply {
                    putBoolean(KEY_RUNNING, service != null)
                    putBoolean(KEY_PLAYING_NATIVELY, service?.playingNatively == true)
                    putBoolean(KEY_PLAYING_FOR_PAGE, service?.playingForPage == true)
                    putBoolean(KEY_HANDING_BACK, service?.handingBack == true)
                }
            }
            METHOD_PLAYBACK_STOP_NATIVE -> {
                main.post { Playback.service?.stopNative() }
                Bundle()
            }
            METHOD_PLAYBACK_CLEAR_LIBRARY -> {
                main.post { Playback.service?.clearLibrary() }
                Bundle()
            }
            METHOD_PLAYBACK_REFRESH_DISCORD -> {
                main.post { Playback.service?.refreshDiscord() }
                Bundle()
            }
            METHOD_PLAYBACK_CLEAR_PAGE_IF_IDLE -> {
                val server = extras?.getString(KEY_SERVER) ?: ""
                main.post {
                    Playback.service?.let { service ->
                        if (!service.playingNatively) service.showPage(null, server)
                    }
                }
                Bundle()
            }

            else -> null
        }
    }

    private fun <T> onDownloadsReady(app: Context, work: () -> T): T? {
        if (Downloads.isReadyOrEmpty(app) || Looper.myLooper() == Looper.getMainLooper()) {
            return work()
        }
        return onMainOrNull(work)
    }

    private fun <T> onMainOrNull(work: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val latch = CountDownLatch(1)
        var result: T? = null
        var failure: Throwable? = null
        main.post {
            try {
                result = work()
            } catch (error: Throwable) {
                failure = error
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(MAIN_WAIT_SECONDS, TimeUnit.SECONDS)) return null
        failure?.let { throw it }
        return result
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val METHOD_PREF_GET_STRING = "pref.get_string"
        const val METHOD_PREF_PUT_STRING = "pref.put_string"
        const val METHOD_PREF_GET_BOOLEAN = "pref.get_boolean"
        const val METHOD_PREF_PUT_BOOLEAN = "pref.put_boolean"
        const val METHOD_PREF_GET_LONG = "pref.get_long"
        const val METHOD_PREF_PUT_LONG = "pref.put_long"
        const val METHOD_PREF_DEVICE_ID = "pref.device_id"
        const val METHOD_PREF_FORGET_CONNECTION = "pref.forget_connection"

        const val METHOD_DOWNLOADS_ADD = "downloads.add"
        const val METHOD_DOWNLOADS_REMOVE = "downloads.remove"
        const val METHOD_DOWNLOADS_REMOVE_ALL = "downloads.remove_all"
        const val METHOD_DOWNLOADS_WIFI_ONLY = "downloads.wifi_only"
        const val METHOD_DOWNLOADS_STATES = "downloads.states"
        const val METHOD_DOWNLOADS_SUMMARY = "downloads.summary"
        const val METHOD_DOWNLOADS_STORED = "downloads.stored"

        const val METHOD_OFFLINE_PLAYS_SEND = "offline_plays.send"

        const val METHOD_PLAYBACK_STATUS = "playback.status"
        const val METHOD_PLAYBACK_STOP_NATIVE = "playback.stop_native"
        const val METHOD_PLAYBACK_CLEAR_LIBRARY = "playback.clear_library"
        const val METHOD_PLAYBACK_REFRESH_DISCORD = "playback.refresh_discord"
        const val METHOD_PLAYBACK_CLEAR_PAGE_IF_IDLE = "playback.clear_page_if_idle"

        const val KEY_VALUE = "value"
        const val KEY_DEFAULT = "default"
        const val KEY_PRESENT = "present"
        const val KEY_REMOVE = "remove"
        const val KEY_SERVER = "server"
        const val KEY_TRACKS_JSON = "tracks_json"
        const val KEY_TRACK_IDS = "track_ids"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_SINCE_VERSION = "since_version"
        const val KEY_VERSION = "version"
        const val KEY_UNCHANGED = "unchanged"
        const val KEY_JSON = "json"
        const val KEY_SUMMARY = "summary"
        const val KEY_RUNNING = "running"
        const val KEY_PLAYING_NATIVELY = "playing_natively"
        const val KEY_PLAYING_FOR_PAGE = "playing_for_page"
        const val KEY_HANDING_BACK = "handing_back"

        private const val MAIN_WAIT_SECONDS = 5L

        fun uri(context: Context): Uri = "content://${context.packageName}.bridge".toUri()

        fun downloadsUri(context: Context): Uri = "content://${context.packageName}.bridge/downloads".toUri()

        fun call(context: Context, method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
            runCatching { context.contentResolver.call(uri(context), method, arg, extras) }.getOrNull()

        fun playbackStatus(context: Context): PlaybackStatus {
            val result = call(context, METHOD_PLAYBACK_STATUS)
            return PlaybackStatus(
                running = result?.getBoolean(KEY_RUNNING, false) ?: false,
                playingNatively = result?.getBoolean(KEY_PLAYING_NATIVELY, false) ?: false,
                playingForPage = result?.getBoolean(KEY_PLAYING_FOR_PAGE, false) ?: false,
                handingBack = result?.getBoolean(KEY_HANDING_BACK, false) ?: false,
            )
        }

        fun stopNative(context: Context) {
            call(context, METHOD_PLAYBACK_STOP_NATIVE)
        }

        fun clearLibrary(context: Context) {
            call(context, METHOD_PLAYBACK_CLEAR_LIBRARY)
        }

        fun refreshDiscord(context: Context) {
            call(context, METHOD_PLAYBACK_REFRESH_DISCORD)
        }

        fun clearPageIfIdle(context: Context, server: String) {
            call(context, METHOD_PLAYBACK_CLEAR_PAGE_IF_IDLE, extras = Bundle().apply { putString(KEY_SERVER, server) })
        }
    }
}
