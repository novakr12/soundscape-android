package io.github.novakr12.soundscape

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Playback {
    var service: PlaybackService? = null
    var runInPage: ((String) -> Unit)? = null
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var pagePlayer: PagePlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var library: CarLibrary
    private val main = Handler(Looper.getMainLooper())
    private val reporter = Executors.newSingleThreadExecutor()
    private var native: Native? = null

    val playingNatively: Boolean get() = native?.live == true
    val playingForPage: Boolean get() = native?.let { it.live && it.fromPage } == true

    override fun onCreate() {
        super.onCreate()
        pagePlayer = PagePlayer(
            Looper.getMainLooper(),
            send = { script -> Playback.runInPage?.invoke(script) },
            busy = { native != null },
            onItems = ::playFromCar,
            where = { name -> getString(R.string.playing_on, name) },
        )
        library = CarLibrary(this)
        val opens = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, pagePlayer, library)
            .setSessionActivity(opens)
            .setBitmapLoader(
                DataSourceBitmapLoader.Builder(this).setDataSourceFactory(DefaultDataSource.Factory(this, withCookies())).build(),
            )
            .build()
        Playback.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    fun showPage(state: PageState?, server: String) {
        pagePlayer.show(state, server)
    }

    fun takeOver(state: PageState, server: String, token: String) {
        if (native != null || state.remote != null) return
        start(state, server, token, fromPage = true)
    }

    fun playDownloads(tracks: List<BridgeTrack>, at: Int) {
        if (tracks.isEmpty()) return
        native?.let {
            native = null
            session.player = pagePlayer
            it.release()
        }
        Playback.runInPage?.invoke(Bridge.command("pause"))
        val settings = Settings(this)
        val state = PageState(tracks, at.coerceIn(tracks.indices), 0.0, null, true, 1.0, "off")
        start(state, settings.server ?: OFFLINE, settings.token.orEmpty(), fromPage = false)
    }

    private fun playFromCar(items: List<MediaItem>, index: Int, positionMs: Long) {
        val tracks = items.mapNotNull { Car.trackFrom(it.mediaMetadata.extras?.getString(CarLibrary.TRACK)) }
        val at = index.coerceAtLeast(0)
        if (tracks.isEmpty() || tracks.size != items.size || at !in tracks.indices) return
        val time = positionMs.coerceAtLeast(0) / 1000.0
        native?.let {
            it.replace(tracks, at, time, true)
            return
        }
        val settings = Settings(this)
        val server = settings.server ?: return
        val token = settings.token ?: return
        val volume = pagePlayer.shown?.volume ?: DEFAULT_VOLUME
        start(PageState(tracks, at, time, null, true, volume, "off"), server, token, fromPage = true).replaced = true
    }

    private fun start(state: PageState, server: String, token: String, fromPage: Boolean): Native {
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(Downloads.playing(this)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setMediaItems(itemsFor(server, state.tracks), state.position, (state.time * 1000).toLong())
        player.repeatMode = Bridge.repeatMode(state.repeat)
        val running = Native(player, state, server, token, fromPage)
        native = running
        player.addListener(running)
        player.volume = running.volumeAt(state.position)
        if (!fromPage) session.player = player
        player.prepare()
        player.playWhenReady = true
        if (fromPage) main.postDelayed({ if (native === running && !running.live) giveUp(running) }, TAKE_OVER_WITHIN_MS)
        return running
    }

    fun handBackState(): HandBack? {
        val running = native?.takeIf { it.live } ?: return null
        val player = running.player
        return HandBack(
            player.currentMediaItemIndex,
            player.currentPosition / 1000.0,
            player.playWhenReady,
            running.tracks.takeIf { running.replaced },
        )
    }

    fun finishHandBack() {
        val running = native ?: return
        native = null
        session.player = pagePlayer
        running.release()
    }

    private fun itemsFor(server: String, tracks: List<BridgeTrack>) =
        tracks.map { CarLibrary.playableItem(this, server, it) }

    private fun giveUp(running: Native) {
        if (native !== running) return
        native = null
        if (session.player === running.player) session.player = pagePlayer
        running.release()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playingNatively || native?.player?.playWhenReady != true) {
            pagePlayer.show(null, "")
            stopSelf()
        }
    }

    override fun onDestroy() {
        native?.release()
        native = null
        session.release()
        library.release()
        pagePlayer.release()
        reporter.shutdown()
        if (Playback.service === this) Playback.service = null
        super.onDestroy()
    }

    private fun withCookies(): DataSource.Factory = DataSource.Factory {
        val cookies = CookieManager.getInstance()
        val factory = DefaultHttpDataSource.Factory().setUserAgent("SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
        val server = Settings(this).server
        val cookie = server?.let { cookies.getCookie(it) }
        if (cookie != null) factory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
        factory.createDataSource()
    }

    private fun report(kind: String, track: BridgeTrack, server: String, token: String) {
        val playedAt = System.currentTimeMillis() / 1000
        reporter.execute {
            val sent = token.isNotEmpty() && server != OFFLINE && runCatching {
                val connection = URL("$server/api/$kind").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.doOutput = true
                connection.setRequestProperty("Authorization", "Token $token")
                connection.setRequestProperty("Content-Type", "application/json")
                val body = JSONObject().put("trackId", track.trackId)
                track.songId?.let { body.put("songId", it) }
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = connection.responseCode
                connection.disconnect()
                status in 200..299 || status == 404
            }.getOrDefault(false)
            if (kind != "play") return@execute
            if (sent) {
                OfflinePlays.send(this)
            } else {
                OfflinePlays.add(this, track, playedAt)
            }
        }
    }

    private inner class Native(
        val player: ExoPlayer,
        val state: PageState,
        val server: String,
        val token: String,
        val fromPage: Boolean,
    ) : Player.Listener {
        var live = false
        var tracks = state.tracks
        var replaced = false
        private var volume = state.volume
        private var link: RemoteLink? = null
        private var heard = 0.0
        private var before: Double? = null
        private var counted = false
        private var announced = -1
        private val tick = object : Runnable {
            override fun run() {
                follow()
                main.postDelayed(this, TICK_MS)
            }
        }

        fun volumeAt(index: Int) = (volume * (tracks.getOrNull(index)?.level ?: 1.0)).toFloat()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            link?.reportSoon()
            if (!isPlaying || live) return
            live = true
            session.player = player
            if (fromPage) Playback.runInPage?.invoke(Bridge.command("pause"))
            Playback.runInPage?.invoke(Remote.holdScript(true))
            connect()
            announce()
            main.post(tick)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            player.volume = volumeAt(player.currentMediaItemIndex)
            heard = 0.0
            before = null
            counted = false
            if (live) announce()
            link?.reportSoon()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            link?.reportSoon()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            link?.reportSoon()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            val now = (0 until player.mediaItemCount).map {
                Car.trackFrom(player.getMediaItemAt(it).mediaMetadata.extras?.getString(CarLibrary.TRACK))
            }
            if (now.isEmpty() || now.any { it == null }) return
            val known = now.filterNotNull()
            if (known.map { it.trackId } == tracks.map { it.trackId }) return
            tracks = known
            replaced = true
            announced = -1
        }

        fun replace(next: List<BridgeTrack>, position: Int, time: Double, playing: Boolean) {
            tracks = next
            replaced = true
            announced = -1
            player.setMediaItems(itemsFor(server, next), position, (time * 1000).toLong())
            player.volume = volumeAt(position)
            player.prepare()
            player.playWhenReady = playing
        }

        private fun connect() {
            if (link != null || token.isEmpty() || server == OFFLINE) return
            val settings = Settings(this@PlaybackService)
            link = RemoteLink(server, token, settings.deviceId, Remote.deviceName(Build.MODEL), ::snapshot, ::obey)
                .also { it.start() }
        }

        private fun snapshot(): RemoteSnapshot {
            val index = player.currentMediaItemIndex
            val track = tracks.getOrNull(index)
            val length = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: track?.duration
            val going = player.playWhenReady &&
                player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE
            return RemoteSnapshot(track, going, player.currentPosition / 1000.0, length, volume)
        }

        private fun obey(command: RemoteCommand) {
            when (command) {
                RemoteCommand.Play -> player.play()
                RemoteCommand.Pause -> player.pause()
                RemoteCommand.Toggle -> if (player.playWhenReady) player.pause() else player.play()
                RemoteCommand.Next -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                RemoteCommand.Previous ->
                    if (player.currentPosition / 1000.0 > Remote.RESTART_AFTER_SECONDS || !player.hasPreviousMediaItem()) {
                        player.seekTo(0)
                    } else {
                        player.seekToPreviousMediaItem()
                    }
                is RemoteCommand.Seek -> player.seekTo((command.seconds * 1000).toLong())
                is RemoteCommand.Volume -> {
                    volume = command.volume
                    player.volume = volumeAt(player.currentMediaItemIndex)
                }
                is RemoteCommand.Take -> replace(command.tracks, command.position, command.time, command.playing)
                is RemoteCommand.Send -> {
                    val body = Remote.send(
                        command.to,
                        tracks,
                        player.currentMediaItemIndex,
                        player.currentPosition / 1000.0,
                    ) ?: return
                    link?.send(body) { sent -> if (sent) player.pause() }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!live && fromPage) giveUp(this)
        }

        private fun announce() {
            val index = player.currentMediaItemIndex
            if (index == announced && player.repeatMode != Player.REPEAT_MODE_ONE) return
            announced = index
            tracks.getOrNull(index)?.let { report("now-playing", it, server, token) }
        }

        private fun follow() {
            if (!player.isPlaying) {
                before = null
                return
            }
            val now = player.currentPosition / 1000.0
            heard += Bridge.heardStep(before, now)
            before = now
            val track = tracks.getOrNull(player.currentMediaItemIndex) ?: return
            val length = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: track.duration
            if (!counted && Bridge.countsAsPlay(heard, length)) {
                counted = true
                report("play", track, server, token)
            }
        }

        fun release() {
            link?.stop()
            link = null
            if (live) Playback.runInPage?.invoke(Remote.holdScript(false))
            main.removeCallbacks(tick)
            player.removeListener(this)
            player.release()
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val TAKE_OVER_WITHIN_MS = 12_000L
        const val OFFLINE = CarLibrary.OFFLINE
        const val DEFAULT_VOLUME = 0.8
    }
}
