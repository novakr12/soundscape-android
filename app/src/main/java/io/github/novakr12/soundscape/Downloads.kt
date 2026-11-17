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
    private var database: StandaloneDatabaseProvider? = null
    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null
    private val known = linkedMapOf<String, Download>()
    private val watchers = mutableSetOf<() -> Unit>()

    fun cache(context: Context): SimpleCache {
        cache?.let { return it }
        val made = SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseOf(context))
        cache = made
        return made
    }

    private fun databaseOf(context: Context): StandaloneDatabaseProvider =
        database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    fun withToken(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return DataSource.Factory {
            val token = Settings(app).token
            val headers = buildMap {
                put("Range", "bytes=0-")
                if (token != null) put("Authorization", "Token $token")
            }
            DefaultHttpDataSource.Factory()
                .setUserAgent("SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
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
        manager?.let { return it }
        val app = context.applicationContext
        val made = DownloadManager(app, databaseOf(app), cache(app), withToken(app), Executors.newFixedThreadPool(2))
        made.maxParallelDownloads = 2
        made.requirements = requirements(Settings(app).downloadsWifiOnly)
        made.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) {
                reload(downloadManager)
            }

            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                known[download.request.id] = download
                tell()
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                known.remove(download.request.id)
                tell()
            }
        })
        manager = made
        if (made.isInitialized) reload(made)
        return made
    }

    private fun reload(downloadManager: DownloadManager) {
        known.clear()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) known[cursor.download.request.id] = cursor.download
        }
        tell()
    }

    private fun tell() = watchers.toList().forEach { it() }

    fun watch(watcher: () -> Unit) {
        watchers += watcher
    }

    fun unwatch(watcher: () -> Unit) {
        watchers -= watcher
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
            val existing = known[track.trackId]
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
            if (known.containsKey(id)) DownloadService.sendRemoveDownload(context, SongDownloadService::class.java, id, false)
        }
    }

    fun removeAll(context: Context) {
        DownloadService.sendRemoveAllDownloads(context, SongDownloadService::class.java, false)
    }

    fun stored(context: Context): List<Stored> {
        manager(context)
        return known.values.mapNotNull { download ->
            val track = DownloadData.decode(download.request.data) ?: return@mapNotNull null
            Stored(track, download.state, download.bytesDownloaded, download.percentDownloaded)
        }
    }

    fun states(context: Context): Map<String, String> = stored(context).mapNotNull { item ->
        when (item.state) {
            Download.STATE_COMPLETED -> item.track.trackId to "done"
            Download.STATE_FAILED -> item.track.trackId to "failed"
            Download.STATE_REMOVING -> null
            else -> item.track.trackId to "waiting"
        }
    }.toMap()

    fun summary(context: Context): String? {
        val done = stored(context).filter { it.state == Download.STATE_COMPLETED }
        return DownloadData.summary(done.size, done.sumOf { it.bytes })
    }

    fun playable(context: Context): List<BridgeTrack> =
        stored(context).filter { it.state == Download.STATE_COMPLETED }.map { it.track }
}
