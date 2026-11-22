package io.github.novakr12.soundscape

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class CarLibrary(
    context: Context,
    private val onCustom: (SessionCommand, Bundle) -> ListenableFuture<SessionResult>? = { _, _ -> null },
) : MediaLibrarySession.Callback {
    private val app = context.applicationContext
    private val pool = MoreExecutors.listeningDecorator(Pools.io)
    private val resolver = Pools.serial("soundscape-car-resolve")
    private val main = Handler(Looper.getMainLooper())
    private val pages = ConcurrentHashMap<String, Pair<Long, CarPage>>()

    @Volatile
    private var released = false

    private sealed class Loaded {
        data class Page(val page: CarPage) : Loaded()
        data class Failed(val error: SessionError) : Loaded()
    }

    fun clear() {
        pages.clear()
    }

    fun release() {
        released = true
        pages.clear()
        resolver.shutdownNow()
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val base = super.onConnect(session, controller)
        if (controller.packageName != app.packageName || session.isMediaNotificationController(controller)) return base
        val commands = base.availableSessionCommands.buildUpon().apply {
            PlaybackBridge.ALL_COMMANDS.forEach { add(it) }
        }.build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(commands)
            .setAvailablePlayerCommands(base.availablePlayerCommands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (controller.packageName == app.packageName) {
            onCustom(customCommand, args)?.let { return it }
        }
        return super.onCustomCommand(session, controller, customCommand, args)
    }

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val extras = Bundle().apply {
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
            putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
            putBoolean(SEARCH_SUPPORTED, true)
        }
        val root = MediaItem.Builder()
            .setMediaId(Car.ROOT)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(app.getString(R.string.app_name))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(root, LibraryParams.Builder().setExtras(extras).build()))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        if (parentId == Car.ROOT) return Futures.immediateFuture(LibraryResult.ofItemList(tabs(), params))
        return Futures.transform(load(parentId), { loaded -> listed(parentId, loaded, page, pageSize, params) }, pool)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val (parent, index) = Car.readSongId(mediaId)
            ?: return Futures.immediateFuture(LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, "")))
        return Futures.transform(load(parent), { loaded ->
            val track = (loaded as? Loaded.Page)?.page?.songs?.getOrNull(index)
            if (track == null) {
                LibraryResult.ofError(SessionError(SessionError.ERROR_BAD_VALUE, ""))
            } else {
                LibraryResult.ofItem(browsableSong(parent, index, track), null)
            }
        }, pool)
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> {
        val node = Car.searchNode(query)
        return Futures.transform(load(node, fresh = true), { loaded ->
            val count = (loaded as? Loaded.Page)?.page?.songs?.size ?: 0
            main.post {
                if (!released) runCatching { session.notifySearchResultChanged(browser, query, count, params) }
            }
            LibraryResult.ofVoid()
        }, pool)
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val node = Car.searchNode(query)
        return Futures.transform(load(node), { loaded -> listed(node, loaded, page, pageSize, params) }, pool)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        val future = SettableFuture.create<MutableList<MediaItem>>()
        try {
            resolver.execute {
                try {
                    val resolved = mediaItems.flatMap { item ->
                        resolve(item)?.let { it.first.subList(it.second, it.second + 1) } ?: emptyList()
                    }
                    future.set(playable(resolved).toMutableList())
                } catch (error: Throwable) {
                    future.setException(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            future.setException(error)
        }
        return future
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        try {
            resolver.execute {
                try {
                    val single = mediaItems.singleOrNull()?.let { resolve(it) }
                    val (tracks, start) = single ?: (
                        mediaItems.mapNotNull { Car.trackFrom(it.mediaMetadata.extras?.getString(TRACK)) } to
                            startIndex.coerceAtLeast(0)
                        )
                    if (tracks.isEmpty() || start !in tracks.indices) {
                        future.setException(IllegalArgumentException("Nothing to play"))
                    } else {
                        val position = if (single != null) 0L else startPositionMs.coerceAtLeast(0)
                        future.set(MediaSession.MediaItemsWithStartPosition(playable(tracks), start, position))
                    }
                } catch (error: Throwable) {
                    future.setException(error)
                }
            }
        } catch (error: RejectedExecutionException) {
            future.setException(error)
        }
        return future
    }

    private fun awaitSongs(node: String, fresh: Boolean = false): List<BridgeTrack>? =
        runCatching { (load(node, fresh).get(RESOLVE_TIMEOUT_SECONDS, TimeUnit.SECONDS) as? Loaded.Page)?.page?.songs }
            .getOrNull()

    private fun resolve(item: MediaItem): Pair<List<BridgeTrack>, Int>? {
        Car.trackFrom(item.mediaMetadata.extras?.getString(TRACK))?.let { track ->
            Car.readSongId(item.mediaId)?.let { (parent, index) ->
                val songs = awaitSongs(parent)
                if (songs != null && index in songs.indices) return songs to index
            }
            return listOf(track) to 0
        }
        Car.readSongId(item.mediaId)?.let { (parent, index) ->
            val songs = awaitSongs(parent) ?: return null
            return if (index in songs.indices) songs to index else null
        }
        if (Car.isPlayableFolder(item.mediaId)) {
            val songs = awaitSongs(item.mediaId, fresh = item.mediaId == Car.SHUFFLE)
            return songs?.takeIf { it.isNotEmpty() }?.let { it to 0 }
        }
        val query = item.requestMetadata.searchQuery?.trim()
        if (query != null) {
            val node = if (query.isEmpty()) Car.SHUFFLE else Car.searchNode(query)
            val songs = awaitSongs(node, fresh = true)
            return songs?.takeIf { it.isNotEmpty() }?.let { it to 0 }
        }
        return null
    }

    private fun playable(tracks: List<BridgeTrack>): List<MediaItem> {
        val server = Settings(app).server ?: OFFLINE
        return tracks.map { playableItem(app, server, it) }
    }

    private fun listed(
        parent: String,
        loaded: Loaded,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): LibraryResult<ImmutableList<MediaItem>> {
        val shown = when (loaded) {
            is Loaded.Failed -> return LibraryResult.ofError(loaded.error)
            is Loaded.Page -> loaded.page
        }
        val size = shown.items.size
        val safePageSize = pageSize.coerceAtLeast(1)
        val from = (page.coerceAtLeast(0).toLong() * safePageSize).coerceAtMost(size.toLong()).toInt()
        val to = (from + safePageSize).coerceAtMost(size)
        var songIndex = 0
        for (i in 0 until from) {
            if (shown.items[i] is CarEntry.Song) songIndex++
        }
        val items = ArrayList<MediaItem>(to - from)
        for (entry in shown.items.subList(from, to)) {
            items.add(
                when (entry) {
                    is CarEntry.Folder -> folder(entry)
                    is CarEntry.Song -> browsableSong(parent, songIndex++, entry.track)
                },
            )
        }
        return LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }

    private fun load(node: String, fresh: Boolean = false): ListenableFuture<Loaded> {
        if (released) {
            return Futures.immediateFuture(Loaded.Failed(SessionError(SessionError.ERROR_SESSION_DISCONNECTED, "")))
        }
        if (node == Car.DOWNLOADS) {
            val future = SettableFuture.create<Loaded>()
            main.post {
                try {
                    val songs = Downloads.playable(app).map { CarEntry.Song(it) }
                    future.set(Loaded.Page(CarPage(true, app.getString(R.string.car_downloads), songs)))
                } catch (error: Throwable) {
                    future.setException(error)
                }
            }
            return future
        }
        val now = System.currentTimeMillis()
        if (!fresh) {
            pages[node]?.takeIf { now - it.first < KEEP_MS }?.let { return Futures.immediateFuture(Loaded.Page(it.second)) }
        }
        return try {
            pool.submit<Loaded> { fetch(node, now) }
        } catch (_: RejectedExecutionException) {
            Futures.immediateFuture(Loaded.Failed(SessionError(SessionError.ERROR_SESSION_DISCONNECTED, "")))
        }
    }

    private fun fetch(node: String, now: Long): Loaded {
        val settings = Settings(app)
        val server = settings.server
        val token = settings.token
        if (server == null || token == null) {
            return Loaded.Failed(
                SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, app.getString(R.string.car_connect_first)),
            )
        }
        if (!Car.isNode(node)) return Loaded.Failed(SessionError(SessionError.ERROR_BAD_VALUE, ""))
        val (status, body) = NoticeWorker.fetch(Car.address(server, node), token)
            ?: return Loaded.Failed(SessionError(SessionError.ERROR_IO, app.getString(R.string.car_offline)))
        if (status == 401) {
            return Loaded.Failed(
                SessionError(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, app.getString(R.string.car_connect_first)),
            )
        }
        val page = Car.page(body)
            ?: return Loaded.Failed(SessionError(SessionError.ERROR_IO, app.getString(R.string.car_offline)))
        if (!page.connected) {
            return Loaded.Failed(SessionError(SessionError.ERROR_SESSION_SETUP_REQUIRED, app.getString(R.string.car_no_navidrome)))
        }
        cachePage(node, now, page)
        return Loaded.Page(page)
    }

    private fun cachePage(node: String, now: Long, page: CarPage) {
        if (pages.size >= MAX_CACHED_PAGES) {
            val cutoff = now - KEEP_MS
            pages.entries.removeIf { it.value.first < cutoff }
            while (pages.size >= MAX_CACHED_PAGES) {
                val oldest = pages.entries.minByOrNull { it.value.first }?.key ?: break
                pages.remove(oldest)
            }
        }
        pages[node] = now to page
    }

    private fun tabs(): ImmutableList<MediaItem> = ImmutableList.of(
        folder(CarEntry.Folder("albums:added", app.getString(R.string.car_new_albums), null, null, false)),
        folder(CarEntry.Folder("playlists", app.getString(R.string.car_playlists), null, null, false)),
        folder(CarEntry.Folder(Car.SHUFFLE, app.getString(R.string.car_shuffle), null, null, true)),
        folder(CarEntry.Folder(Car.DOWNLOADS, app.getString(R.string.car_downloads), null, null, true)),
    )

    private fun folder(entry: CarEntry.Folder): MediaItem {
        val type = when {
            entry.node.startsWith("album:") -> MediaMetadata.MEDIA_TYPE_ALBUM
            entry.node.startsWith("playlist:") -> MediaMetadata.MEDIA_TYPE_PLAYLIST
            entry.node.startsWith("albums:") -> MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS
            entry.node == "playlists" -> MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS
            else -> MediaMetadata.MEDIA_TYPE_FOLDER_MIXED
        }
        return MediaItem.Builder()
            .setMediaId(entry.node)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setSubtitle(entry.subtitle)
                    .setArtist(entry.subtitle)
                    .setArtworkUri(Car.coverUri(authority(app), entry.cover)?.toUri())
                    .setIsBrowsable(true)
                    .setIsPlayable(entry.playable)
                    .setMediaType(type)
                    .build(),
            )
            .build()
    }

    private fun browsableSong(parent: String, index: Int, track: BridgeTrack): MediaItem = MediaItem.Builder()
        .setMediaId(Car.songId(parent, index))
        .setMediaMetadata(metadata(app, track))
        .build()

    companion object {
        const val TRACK = "soundscape.track"
        const val OFFLINE = "https://offline.invalid"
        private const val SEARCH_SUPPORTED = "android.media.browse.SEARCH_SUPPORTED"
        private const val KEEP_MS = 5 * 60 * 1000L
        private const val RESOLVE_TIMEOUT_SECONDS = 15L
        private const val MAX_CACHED_PAGES = 25

        fun authority(context: Context) = "${context.packageName}.covers"

        fun metadata(context: Context, track: BridgeTrack, server: String? = null): MediaMetadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(
                (Car.coverUri(authority(context), track.cover) ?: server?.let { Bridge.coverAddress(it, track.cover) })?.toUri(),
            )
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putString(TRACK, Car.trackJson(track)) })
            .build()

        fun playableItem(context: Context, server: String, track: BridgeTrack): MediaItem = MediaItem.Builder()
            .setMediaId(track.trackId)
            .setUri(Bridge.streamAddress(server, track))
            .setCustomCacheKey(track.trackId)
            .setMediaMetadata(metadata(context, track, server.takeIf { it != OFFLINE }))
            .build()
    }
}
