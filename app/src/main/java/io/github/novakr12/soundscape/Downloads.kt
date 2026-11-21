package io.github.novakr12.soundscape

import android.content.Context
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
import java.util.concurrent.Executors

data class Stored(val track: BridgeTrack, val state: Int, val bytes: Long, val percent: Float)

@OptIn(UnstableApi::class)
object Downloads {
    private val lock = Any()
    private var database: StandaloneDatabaseProvider? = null
    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private val known = linkedMapOf<String, Download>()
    private val watchers = mutableSetOf<() -> Unit>()
    private var cachedStored: List<Stored>? = null
    private var cachedStates: Map<String, String>? = null
    private var cachedSummary: String? = null
    private var summaryCached = false

    fun cache(context: Context): SimpleCache = synchronized(lock) {
        cache ?: run {
            val app = context.applicationContext
            SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), databaseOf(app)).also { cache = it }
        }
    }

    private fun databaseOf(context: Context): StandaloneDatabaseProvider = synchronized(lock) {
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
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

    fun playing(context: Context): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(withToken(context))
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun manager(context: Context): DownloadManager {
        synchronized(lock) {
            manager?.let { return it }
            val app = context.applicationContext
            val made = DownloadManager(app, databaseOf(app), cache(app), withToken(app), Executors.newFixedThreadPool(2))
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
    }

    private fun storedLocked(): List<Stored> = cachedStored ?: known.values.mapNotNull { download ->
        val track = DownloadData.decode(download.request.data) ?: return@mapNotNull null
        Stored(track, download.state, download.bytesDownloaded, download.percentDownloaded)
    }.also { cachedStored = it }

    private fun tell() {
        val current = synchronized(lock) { watchers.toList() }
        current.forEach { it() }
    }

    fun watch(watcher: () -> Unit) {
        synchronized(lock) { watchers += watcher }
    }

    fun unwatch(watcher: () -> Unit) {
        synchronized(lock) { watchers -= watcher }
    }

    fun requirements(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun setWifiOnly(context: Context, wifiOnly: Boolean) {
        Settings(context).downloadsWifiOnly = wifiOnly
        DownloadService.sendSetRequirements(context, SongDownloadService::class.java, requirements(wifiOnly), false)
    }

    fun add(context: Context, server: String, tracks: List<BridgeTrack>) {
        manager(context)
        for (track in tracks) {
            val existing = synchronized(lock) { known[track.trackId] }
            if (existing != null && existing.state != Download.STATE_FAILED) continue
            val request = DownloadRequest.Builder(track.trackId, Bridge.streamAddress(server, track).toUri())
                .setCustomCacheKey(track.trackId)
                .setData(DownloadData.encode(track))
                .build()
            DownloadService.sendAddDownload(context, SongDownloadService::class.java, request, false)
        }
    }

    fun remove(context: Context, trackIds: List<String>) {
        manager(context)
        for (id in trackIds) {
            if (synchronized(lock) { known.containsKey(id) }) {
                DownloadService.sendRemoveDownload(context, SongDownloadService::class.java, id, false)
            }
        }
    }

    fun removeAll(context: Context) {
        DownloadService.sendRemoveAllDownloads(context, SongDownloadService::class.java, false)
    }

    fun stored(context: Context): List<Stored> {
        manager(context)
        return synchronized(lock) { storedLocked() }
    }

    fun states(context: Context): Map<String, String> {
        manager(context)
        return synchronized(lock) {
            cachedStates ?: storedLocked().mapNotNull { item ->
                when (item.state) {
                    Download.STATE_COMPLETED -> item.track.trackId to "done"
                    Download.STATE_FAILED -> item.track.trackId to "failed"
                    Download.STATE_REMOVING -> null
                    else -> item.track.trackId to "waiting"
                }
            }.toMap().also { cachedStates = it }
        }
    }

    fun summary(context: Context): String? {
        manager(context)
        return synchronized(lock) {
            if (summaryCached) return cachedSummary
            val done = storedLocked().filter { it.state == Download.STATE_COMPLETED }
            DownloadData.summary(done.size, done.sumOf { it.bytes }).also {
                cachedSummary = it
                summaryCached = true
            }
        }
    }

    fun playable(context: Context): List<BridgeTrack> =
        stored(context).filter { it.state == Download.STATE_COMPLETED }.map { it.track }
}
