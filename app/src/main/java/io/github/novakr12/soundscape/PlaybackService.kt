package io.github.novakr12.soundscape

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object Playback {
    var service: PlaybackService? = null
    var runInPage: ((String) -> Unit)? = null
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var pagePlayer: PagePlayer
    private lateinit var session: MediaSession
    private val main = Handler(Looper.getMainLooper())
    private val reporter = Executors.newSingleThreadExecutor()
    private var native: Native? = null

    val playingNatively: Boolean get() = native?.live == true

    override fun onCreate() {
        super.onCreate()
        pagePlayer = PagePlayer(Looper.getMainLooper()) { script -> Playback.runInPage?.invoke(script) }
        val opens = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, pagePlayer)
            .setSessionActivity(opens)
            .setBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(withCookies()).build())
            .build()
        Playback.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    fun showPage(state: PageState?, server: String) {
        pagePlayer.show(state, server)
    }

    fun takeOver(state: PageState, server: String, token: String) {
        if (native != null) return
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(streams(token)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val items = state.tracks.map { PagePlayer.mediaItemFor(server, it, Bridge.streamAddress(server, it)) }
        player.setMediaItems(items, state.position, (state.time * 1000).toLong())
        player.repeatMode = Bridge.repeatMode(state.repeat)
        val running = Native(player, state, server, token)
        native = running
        player.addListener(running)
        player.volume = running.volumeAt(state.position)
        player.prepare()
        player.playWhenReady = true
        main.postDelayed({ if (native === running && !running.live) giveUp(running) }, TAKE_OVER_WITHIN_MS)
    }

    fun handBackState(): HandBack? {
        val running = native?.takeIf { it.live } ?: return null
        val player = running.player
        return HandBack(player.currentMediaItemIndex, player.currentPosition / 1000.0, player.playWhenReady)
    }

    fun finishHandBack() {
        val running = native ?: return
        native = null
        session.player = pagePlayer
        running.release()
    }

    private fun giveUp(running: Native) {
        if (native !== running) return
        native = null
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
        pagePlayer.release()
        reporter.shutdown()
        if (Playback.service === this) Playback.service = null
        super.onDestroy()
    }

    private fun streams(token: String): DataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(mapOf("Authorization" to "Token $token", "Range" to "bytes=0-"))

    private fun withCookies(): DataSource.Factory = DataSource.Factory {
        val cookies = CookieManager.getInstance()
        val factory = DefaultHttpDataSource.Factory().setUserAgent("SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
        val server = Settings(this).server
        val cookie = server?.let { cookies.getCookie(it) }
        if (cookie != null) factory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
        factory.createDataSource()
    }

    private fun report(kind: String, track: BridgeTrack, server: String, token: String) {
        reporter.execute {
            runCatching {
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
                connection.responseCode
                connection.disconnect()
            }
        }
    }

    private inner class Native(
        val player: ExoPlayer,
        val state: PageState,
        val server: String,
        val token: String,
    ) : Player.Listener {
        var live = false
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

        fun volumeAt(index: Int) = (state.volume * (state.tracks.getOrNull(index)?.level ?: 1.0)).toFloat()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying || live) return
            live = true
            session.player = player
            Playback.runInPage?.invoke(Bridge.command("pause"))
            announce()
            main.post(tick)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            player.volume = volumeAt(player.currentMediaItemIndex)
            heard = 0.0
            before = null
            counted = false
            if (live) announce()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!live) giveUp(this)
        }

        private fun announce() {
            val index = player.currentMediaItemIndex
            if (index == announced && player.repeatMode != Player.REPEAT_MODE_ONE) return
            announced = index
            state.tracks.getOrNull(index)?.let { report("now-playing", it, server, token) }
        }

        private fun follow() {
            if (!player.isPlaying) {
                before = null
                return
            }
            val now = player.currentPosition / 1000.0
            heard += Bridge.heardStep(before, now)
            before = now
            val track = state.tracks.getOrNull(player.currentMediaItemIndex) ?: return
            val length = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 } ?: track.duration
            if (!counted && Bridge.countsAsPlay(heard, length)) {
                counted = true
                report("play", track, server, token)
            }
        }

        fun release() {
            main.removeCallbacks(tick)
            player.removeListener(this)
            player.release()
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val TAKE_OVER_WITHIN_MS = 12_000L
    }
}
