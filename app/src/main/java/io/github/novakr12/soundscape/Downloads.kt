package io.github.novakr12.soundscape

import android.content.Context
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class Stored(val track: BridgeTrack, val state: Int, val bytes: Long, val percent: Float)

@OptIn(UnstableApi::class)
object Downloads {
    private const val ADD_CHUNK_SIZE = 200
    private const val REMOVE_CHUNK_SIZE = 500
    private val lock = Any()
    private val version = AtomicLong(1L)
    private var appContext: Context? = null
    private var database: StandaloneDatabaseProvider? = null
    private var cache: SimpleCache? = null
    private var cacheFactory: CacheDataSource.Factory? = null
    private var manager: DownloadManager? = null
    private val known = linkedMapOf<String, Download>()
    private val watchers = mutableSetOf<() -> Unit>()
    private var cachedStored: List<Stored>? = null
    private var cachedStates: Map<String, String>? = null
    private var cachedSummary: String? = null
    private var summaryCached = false

    private val remoteLock = Any()
    private var remoteStatesVersion = -1L
    private var remoteStates = emptyMap<String, String>()
    private var remoteSummaryVersion = -1L
    private var remoteSummary: String? = null
    private var remoteStoredVersion = -1L
    private var remoteStored = emptyList<Stored>()
    private var observer: ContentObserver? = null

    fun cache(context: Context): SimpleCache = synchronized(lock) {
        cache ?: run {
            val app = context.applicationContext
            SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseOf(app)).also { cache = it }
        }
    }

    private fun databaseOf(context: Context): StandaloneDatabaseProvider = synchronized(lock) {
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
    }

    private fun hasDownloadStore(context: Context): Boolean {
        synchronized(lock) {
            if (cache != null || manager != null) return true
        }
        val app = context.applicationContext
        val dir = File(app.filesDir, "downloads")
        if (dir.isDirectory && dir.list()?.isNotEmpty() == true) return true
        return app.getDatabasePath("exoplayer_internal.db").exists()
    }

    internal fun isReadyOrEmpty(context: Context): Boolean {
        synchronized(lock) {
            if (manager != null) return true
        }
        return !hasDownloadStore(context)
    }

    internal fun currentVersion(context: Context): Long {
        if (!hasDownloadStore(context)) return 0L
        manager(context)
        return version.get()
    }

    fun withToken(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return DataSource.Factory {
            val token = Settings(app).token
            val headers = buildMap {
                put("Range", "bytes=0-")
                if (token != null) put("Authorization", "Token $token")
            }
            DefaultHttpDataSource.Factory()
                .setUserAgent(Net.userAgent)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(headers)
                .createDataSource()
        }
    }

    fun playing(context: Context): DataSource.Factory {
        val app = context.applicationContext
        val upstream = withToken(app)
        return DataSource.Factory {
            if (!hasDownloadStore(app)) {
                upstream.createDataSource()
            } else {
                cacheDataSourceFactory(app, upstream).createDataSource()
            }
        }
    }

    private fun cacheDataSourceFactory(app: Context, upstream: DataSource.Factory): CacheDataSource.Factory =
        synchronized(lock) {
            cacheFactory ?: CacheDataSource.Factory()
                .setCache(cache(app))
                .setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .also { cacheFactory = it }
        }

    fun manager(context: Context): DownloadManager {
        synchronized(lock) {
            manager?.let { return it }
            val app = context.applicationContext
            appContext = app
            val made = DownloadManager(
                app,
                databaseOf(app),
                cache(app),
                withToken(app),
                Pools.bounded("soundscape-dl", 2),
            )
            made.maxParallelDownloads = 2
            made.requirements = requirements(Settings(app).downloadsWifiOnly)
            made.addListener(object : DownloadManager.Listener {
                override fun onInitialized(downloadManager: DownloadManager) {
                    reload(downloadManager)
                }

                override fun onDownloadChanged(
                    downloadManager: DownloadManager,
                    download: Download,
                    finalException: Exception?,
                ) {
                    synchronized(lock) {
                        known[download.request.id] = download
                        invalidateLocked()
                    }
                    tell()
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    synchronized(lock) {
                        known.remove(download.request.id)
                        invalidateLocked()
                    }
                    tell()
                }
            })
            loadIndexLocked(made)
            manager = made
            return made
        }
    }

    private fun loadIndexLocked(downloadManager: DownloadManager) {
        known.clear()
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) known[cursor.download.request.id] = cursor.download
            }
        }
        invalidateLocked()
    }

    private fun reload(downloadManager: DownloadManager) {
        val loaded = linkedMapOf<String, Download>()
        runCatching {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) loaded[cursor.download.request.id] = cursor.download
            }
        }
        synchronized(lock) {
            known.clear()
            known.putAll(loaded)
            invalidateLocked()
        }
        tell()
    }

    private fun invalidateLocked() {
        cachedStored = null
        cachedStates = null
        cachedSummary = null
        summaryCached = false
        version.incrementAndGet()
    }

    private fun invalidateRemoteCache() {
        synchronized(remoteLock) {
            remoteStatesVersion = -1L
            remoteSummaryVersion = -1L
            remoteStoredVersion = -1L
        }
    }

    private fun storedLocked(): List<Stored> = cachedStored ?: known.values.mapNotNull { download ->
        val track = DownloadData.decode(download.request.data) ?: return@mapNotNull null
        Stored(track, download.state, download.bytesDownloaded, download.percentDownloaded)
    }.also { cachedStored = it }

    private fun tell() {
        val current = synchronized(lock) { watchers.toList() }
        current.forEach { it() }
        appContext?.let { app ->
            runCatching { app.contentResolver.notifyChange(AppBridgeProvider.downloadsUri(app), null) }
        }
    }

    fun watch(context: Context, watcher: () -> Unit) {
        val app = context.applicationContext
        if (SoundscapeApp.isPlaybackProcess(app)) {
            synchronized(lock) { watchers += watcher }
            return
        }
        val register = synchronized(remoteLock) {
            val wasEmpty = watchers.isEmpty()
            synchronized(lock) { watchers += watcher }
            wasEmpty && observer == null
        }
        if (register) {
            val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    invalidateRemoteCache()
                    val current = synchronized(lock) { watchers.toList() }
                    current.forEach { it() }
                }
            }
            val registered = runCatching {
                app.contentResolver.registerContentObserver(AppBridgeProvider.downloadsUri(app), false, created)
                true
            }.getOrDefault(false)
            if (registered) {
                synchronized(remoteLock) {
                    if (watchers.isEmpty()) {
                        runCatching { app.contentResolver.unregisterContentObserver(created) }
                    } else {
                        observer = created
                    }
                }
            }
        }
    }

    fun unwatch(context: Context, watcher: () -> Unit) {
        val app = context.applicationContext
        if (SoundscapeApp.isPlaybackProcess(app)) {
            synchronized(lock) { watchers -= watcher }
            return
        }
        val toUnregister = synchronized(remoteLock) {
            synchronized(lock) { watchers -= watcher }
            if (watchers.isEmpty()) observer.also { observer = null } else null
        }
        toUnregister?.let { runCatching { app.contentResolver.unregisterContentObserver(it) } }
    }

    fun requirements(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            AppBridgeProvider.call(
                app,
                AppBridgeProvider.METHOD_DOWNLOADS_WIFI_ONLY,
                extras = Bundle().apply { putBoolean(AppBridgeProvider.KEY_WIFI_ONLY, wifiOnly) },
            )
            return
        }
        Settings(app).downloadsWifiOnly = wifiOnly
        if (hasDownloadStore(app)) {
            DownloadService.sendSetRequirements(app, SongDownloadService::class.java, requirements(wifiOnly), false)
        }
    }

    fun add(context: Context, server: String, tracks: List<BridgeTrack>) {
        if (tracks.isEmpty()) return
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            tracks.chunked(ADD_CHUNK_SIZE).forEach { chunk ->
                AppBridgeProvider.call(
                    app,
                    AppBridgeProvider.METHOD_DOWNLOADS_ADD,
                    extras = Bundle().apply {
                        putString(AppBridgeProvider.KEY_SERVER, server)
                        putString(AppBridgeProvider.KEY_TRACKS_JSON, PlaybackCodecs.encodeTracks(chunk))
                    },
                )
            }
            invalidateRemoteCache()
            return
        }
        manager(app)
        for (track in tracks) {
            val existing = synchronized(lock) { known[track.trackId] }
            if (existing != null && existing.state != Download.STATE_FAILED) continue
            val request = DownloadRequest.Builder(track.trackId, Bridge.streamAddress(server, track).toUri())
                .setCustomCacheKey(track.trackId)
                .setData(DownloadData.encode(track))
                .build()
            DownloadService.sendAddDownload(app, SongDownloadService::class.java, request, false)
        }
    }

    fun remove(context: Context, trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            trackIds.chunked(REMOVE_CHUNK_SIZE).forEach { chunk ->
                AppBridgeProvider.call(
                    app,
                    AppBridgeProvider.METHOD_DOWNLOADS_REMOVE,
                    extras = Bundle().apply { putStringArrayList(AppBridgeProvider.KEY_TRACK_IDS, ArrayList(chunk)) },
                )
            }
            invalidateRemoteCache()
            return
        }
        if (!hasDownloadStore(app)) return
        manager(app)
        for (id in trackIds) {
            if (synchronized(lock) { known.containsKey(id) }) {
                DownloadService.sendRemoveDownload(app, SongDownloadService::class.java, id, false)
            }
        }
    }

    fun removeAll(context: Context) {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            AppBridgeProvider.call(app, AppBridgeProvider.METHOD_DOWNLOADS_REMOVE_ALL)
            invalidateRemoteCache()
            return
        }
        if (!hasDownloadStore(app)) return
        DownloadService.sendRemoveAllDownloads(app, SongDownloadService::class.java, false)
    }

    fun stored(context: Context): List<Stored> = storedWithVersion(context).first

    internal fun storedWithVersion(context: Context): Pair<List<Stored>, Long> {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            val since = synchronized(remoteLock) { remoteStoredVersion }
            val result = AppBridgeProvider.call(
                app,
                AppBridgeProvider.METHOD_DOWNLOADS_STORED,
                extras = Bundle().apply { putLong(AppBridgeProvider.KEY_SINCE_VERSION, since) },
            ) ?: return synchronized(remoteLock) { remoteStored to remoteStoredVersion }
            if (result.getBoolean(AppBridgeProvider.KEY_UNCHANGED, false)) {
                return synchronized(remoteLock) { remoteStored to remoteStoredVersion }
            }
            val parsed = PlaybackCodecs.decodeStoredList(result.getString(AppBridgeProvider.KEY_JSON))
            val nextVersion = result.getLong(AppBridgeProvider.KEY_VERSION, -1L)
            synchronized(remoteLock) {
                remoteStored = parsed
                remoteStoredVersion = nextVersion
            }
            return parsed to nextVersion
        }
        if (!hasDownloadStore(app)) return emptyList<Stored>() to 0L
        manager(app)
        return synchronized(lock) { storedLocked() to version.get() }
    }

    fun states(context: Context): Map<String, String> = statesWithVersion(context).first

    internal fun statesWithVersion(context: Context): Pair<Map<String, String>, Long> {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            val since = synchronized(remoteLock) { remoteStatesVersion }
            val result = AppBridgeProvider.call(
                app,
                AppBridgeProvider.METHOD_DOWNLOADS_STATES,
                extras = Bundle().apply { putLong(AppBridgeProvider.KEY_SINCE_VERSION, since) },
            ) ?: return synchronized(remoteLock) { remoteStates to remoteStatesVersion }
            if (result.getBoolean(AppBridgeProvider.KEY_UNCHANGED, false)) {
                return synchronized(remoteLock) { remoteStates to remoteStatesVersion }
            }
            val parsed = PlaybackCodecs.decodeStringMap(result.getString(AppBridgeProvider.KEY_JSON))
            val nextVersion = result.getLong(AppBridgeProvider.KEY_VERSION, -1L)
            synchronized(remoteLock) {
                remoteStates = parsed
                remoteStatesVersion = nextVersion
            }
            return parsed to nextVersion
        }
        if (!hasDownloadStore(app)) return emptyMap<String, String>() to 0L
        manager(app)
        return synchronized(lock) {
            val map = cachedStates ?: storedLocked().mapNotNull { item ->
                when (item.state) {
                    Download.STATE_COMPLETED -> item.track.trackId to "done"
                    Download.STATE_FAILED -> item.track.trackId to "failed"
                    Download.STATE_REMOVING -> null
                    else -> item.track.trackId to "waiting"
                }
            }.toMap().also { cachedStates = it }
            map to version.get()
        }
    }

    fun summary(context: Context): String? = summaryWithVersion(context).first

    internal fun summaryWithVersion(context: Context): Pair<String?, Long> {
        val app = context.applicationContext
        if (!SoundscapeApp.isPlaybackProcess(app)) {
            val since = synchronized(remoteLock) { remoteSummaryVersion }
            val result = AppBridgeProvider.call(
                app,
                AppBridgeProvider.METHOD_DOWNLOADS_SUMMARY,
                extras = Bundle().apply { putLong(AppBridgeProvider.KEY_SINCE_VERSION, since) },
            ) ?: return synchronized(remoteLock) { remoteSummary to remoteSummaryVersion }
            if (result.getBoolean(AppBridgeProvider.KEY_UNCHANGED, false)) {
                return synchronized(remoteLock) { remoteSummary to remoteSummaryVersion }
            }
            val parsed = result.getString(AppBridgeProvider.KEY_SUMMARY)
            val nextVersion = result.getLong(AppBridgeProvider.KEY_VERSION, -1L)
            synchronized(remoteLock) {
                remoteSummary = parsed
                remoteSummaryVersion = nextVersion
            }
            return parsed to nextVersion
        }
        if (!hasDownloadStore(app)) return null to 0L
        manager(app)
        return synchronized(lock) {
            val text = if (summaryCached) {
                cachedSummary
            } else {
                val done = storedLocked().filter { it.state == Download.STATE_COMPLETED }
                DownloadData.summary(done.size, done.sumOf { it.bytes }).also {
                    cachedSummary = it
                    summaryCached = true
                }
            }
            text to version.get()
        }
    }

    fun playable(context: Context): List<BridgeTrack> =
        stored(context).filter { it.state == Download.STATE_COMPLETED }.map { it.track }
}
