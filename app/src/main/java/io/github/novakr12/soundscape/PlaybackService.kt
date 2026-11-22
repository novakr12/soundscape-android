package io.github.novakr12.soundscape

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.concurrent.RejectedExecutionException

object Playback {
    @Volatile
    var service: PlaybackService? = null
}

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private lateinit var pagePlayer: PagePlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var library: CarLibrary
    private val main = Handler(Looper.getMainLooper())
    private val reporter = Pools.serial("soundscape-report")
    private var discord: DiscordLink? = null
    private val spectrum = Spectrum()
    private var spectrumSent: ByteArray? = null
    private val showSpectrum = object : Runnable {
        override fun run() {
            if (!spectrum.on) return
            val target = pageController()
            if (target == null) {
                spectrum.on = false
                return
            }
            val bins = spectrum.latest.takeIf { native?.player?.isPlaying == true }
            if (bins != null && bins !== spectrumSent) {
                spectrumSent = bins
                session.sendCustomCommand(
                    target,
                    PlaybackBridge.EVENT_SPECTRUM,
                    Bundle().apply { putByteArray(PlaybackBridge.KEY_BINS, bins) },
                )
            }
            main.postDelayed(this, SPECTRUM_TICK_MS)
        }
    }

    @Volatile
    private var native: Native? = null

    @Volatile
    private var destroyed = false

    val playingNatively: Boolean get() = native?.live == true
    val playingForPage: Boolean
        get() {
            val running = native ?: return false
            return running.live && running.fromPage
        }

    override fun onCreate() {
        super.onCreate()
        pagePlayer = PagePlayer(
            Looper.getMainLooper(),
            send = ::sendRunInPage,
            busy = { native != null },
            onItems = ::playFromCar,
            where = { name -> getString(R.string.playing_on, name) },
        )
        library = CarLibrary(this, ::handleCustomCommand)
        val opens = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader.Builder(this)
                .setDataSourceFactory(DefaultDataSource.Factory(this, withTokenForOwnOrigin()))
                .setMaximumOutputDimension(MAX_ARTWORK_DIM_PX)
                .build(),
        )
        session = MediaLibrarySession.Builder(this, pagePlayer, library)
            .setSessionActivity(opens)
            .setBitmapLoader(bitmapLoader)
            .build()
        Playback.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    private fun handleCustomCommand(command: SessionCommand, args: Bundle): ListenableFuture<SessionResult>? {
        val result = when (command.customAction) {
            PlaybackBridge.ACTION_SHOW_PAGE -> {
                val state = args.getString(PlaybackBridge.KEY_STATE_JSON)?.let(Bridge::parse)
                val server = args.getString(PlaybackBridge.KEY_SERVER) ?: ""
                showPage(state, server)
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            PlaybackBridge.ACTION_TAKE_OVER -> {
                val state = Bridge.parse(args.getString(PlaybackBridge.KEY_STATE_JSON))
                val server = args.getString(PlaybackBridge.KEY_SERVER)
                if (state == null || server.isNullOrEmpty()) {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                } else {
                    val token = args.getString(PlaybackBridge.KEY_TOKEN) ?: ""
                    val readAt = args.getLong(PlaybackBridge.KEY_READ_AT, SystemClock.elapsedRealtime())
                    takeOver(state, server, token, readAt)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            PlaybackBridge.ACTION_OBEY_PAGE -> {
                val item = args.getString(PlaybackBridge.KEY_COMMAND_JSON)
                    ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
                val parsed = Remote.command(item)
                if (parsed == null) {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                } else {
                    obeyPage(parsed)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            PlaybackBridge.ACTION_TELL_PAGE -> {
                val report = currentReportJson()
                tellPage()
                SessionResult(
                    SessionResult.RESULT_SUCCESS,
                    Bundle().apply { report?.let { putString(PlaybackBridge.KEY_REPORT_JSON, it) } },
                )
            }
            PlaybackBridge.ACTION_PLAY_DOWNLOADS -> {
                val tracks = PlaybackCodecs.decodeTracks(args.getString(PlaybackBridge.KEY_TRACKS_JSON))
                val index = args.getInt(PlaybackBridge.KEY_INDEX, 0)
                if (tracks.isEmpty()) {
                    SessionResult(SessionError.ERROR_BAD_VALUE)
                } else {
                    playDownloads(tracks, index)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            PlaybackBridge.ACTION_HAND_BACK_STATE -> {
                val back = handBackState()
                SessionResult(
                    SessionResult.RESULT_SUCCESS,
                    Bundle().apply {
                        back?.let { putString(PlaybackBridge.KEY_HAND_BACK_JSON, PlaybackCodecs.encodeHandBack(it)) }
                    },
                )
            }
            PlaybackBridge.ACTION_HAND_BACK_START -> {
                startHandBack()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            PlaybackBridge.ACTION_HAND_BACK_ABANDON -> {
                abandonHandBack()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            PlaybackBridge.ACTION_HAND_BACK_FINISH -> {
                finishHandBack()
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            PlaybackBridge.ACTION_WATCH_SPECTRUM -> {
                watchSpectrum(args.getBoolean(PlaybackBridge.KEY_ON))
                SessionResult(SessionResult.RESULT_SUCCESS)
            }
            else -> return null
        }
        return Futures.immediateFuture(result)
    }

    private fun pageController(): MediaSession.ControllerInfo? {
        if (!::session.isInitialized) return null
        return session.connectedControllers.lastOrNull { controller ->
            controller.packageName == packageName &&
                controller.connectionHints.getBoolean(PlaybackBridge.HINT_PAGE_CLIENT, false)
        }
    }

    private fun sendRunInPage(script: String) {
        if (destroyed) return
        val target = pageController() ?: return
        session.sendCustomCommand(
            target,
            PlaybackBridge.EVENT_RUN_IN_PAGE,
            Bundle().apply { putString(PlaybackBridge.KEY_SCRIPT, script) },
        )
    }

    private fun sendReportToPage(json: String) {
        if (destroyed) return
        val target = pageController() ?: return
        session.sendCustomCommand(
            target,
            PlaybackBridge.EVENT_REPORT_TO_PAGE,
            Bundle().apply { putString(PlaybackBridge.KEY_REPORT_JSON, json) },
        )
    }

    private fun watchSpectrum(on: Boolean) {
        main.removeCallbacks(showSpectrum)
        spectrum.on = on && !destroyed
        spectrumSent = null
        if (spectrum.on) main.post(showSpectrum)
    }

    private fun sendNativeState() {
        if (destroyed) return
        val target = pageController() ?: return
        session.sendCustomCommand(
            target,
            PlaybackBridge.EVENT_NATIVE_STATE,
            Bundle().apply {
                putBoolean(PlaybackBridge.KEY_PLAYING_NATIVELY, playingNatively)
                putBoolean(PlaybackBridge.KEY_PLAYING_FOR_PAGE, playingForPage)
            },
        )
    }

    private fun requestReadPage(reply: (PageState?) -> Unit) {
        if (destroyed) {
            reply(null)
            return
        }
        val target = pageController()
        if (target == null) {
            reply(null)
            return
        }
        val future = session.sendCustomCommand(target, PlaybackBridge.EVENT_READ_PAGE, Bundle.EMPTY)
        future.addListener({
            val raw = runCatching { future.get().extras.getString(PlaybackBridge.KEY_STATE_JSON) }.getOrNull()
            reply(Bridge.parse(raw))
        }, ContextCompat.getMainExecutor(this))
    }

    fun showPage(state: PageState?, server: String) {
        if (destroyed || !::pagePlayer.isInitialized) return
        pagePlayer.show(state, server)
        if (!playingNatively) updateDiscord()
    }

    fun refreshDiscord() {
        updateDiscord(force = true)
    }

    fun stopNative() {
        handingBack = false
        val running = native ?: return
        native = null
        if (::session.isInitialized && ::pagePlayer.isInitialized && session.player === running.player) {
            session.player = pagePlayer
        }
        running.release()
        sendNativeState()
    }

    fun clearLibrary() {
        if (::library.isInitialized) library.clear()
    }

    private fun updateDiscord(force: Boolean = false) {
        if (destroyed) return
        val settings = Settings(this)
        val wanted = settings.discord && settings.discordAppId?.let(Discord::validAppId) == true
        if (!wanted) {
            discord?.stop()
            discord = null
            return
        }
        val link = discord ?: DiscordLink(this).also { discord = it }
        link.update(currentDiscord(), force = force)
    }

    private fun currentDiscord(): DiscordSource? {
        val running = native
        if (running != null && running.live) return running.discordSource()
        if (!::pagePlayer.isInitialized) return null
        val state = pagePlayer.shown
        if (state != null && state.remote == null && state.playing) {
            val track = state.current
            val settings = Settings(this)
            return DiscordSource(
                playing = DiscordPlaying(
                    song = DiscordSong(track.title, track.artist, track.album),
                    position = state.time,
                    length = state.duration ?: track.duration,
                    art = track.cover,
                ),
                server = settings.server,
                token = settings.token,
            )
        }
        return null
    }

    fun takeOver(state: PageState, server: String, token: String, readAt: Long) {
        if (destroyed || native != null || state.remote != null) return
        start(state, server, token, fromPage = true, catchUp = state.playing).since =
            Bridge.readMoment(readAt, SystemClock.elapsedRealtime())
    }

    fun obeyPage(command: RemoteCommand) {
        if (destroyed) return
        native?.let {
            it.obeyHere(command)
            return
        }
        val take = when (command) {
            is RemoteCommand.Take -> command
            is RemoteCommand.Enqueue -> RemoteCommand.Take(listOf(command.track), 0, 0.0, true)
            else -> return
        }
        val settings = Settings(this)
        val server = settings.server ?: return
        val volume = pagePlayer.shown?.volume ?: DEFAULT_VOLUME
        val state = PageState(take.tracks, take.position, take.time, null, take.playing, volume, "off")
        start(state, server, settings.token.orEmpty(), fromPage = false, playWhenReady = take.playing)
        tellPage()
    }

    private fun currentReportJson(): String? {
        if (destroyed || !::pagePlayer.isInitialized) return null
        return native?.report() ?: Remote.report(
            Settings(this).deviceId,
            Remote.deviceName(Build.MODEL),
            RemoteSnapshot(null, false, 0.0, null, pagePlayer.shown?.volume ?: DEFAULT_VOLUME),
        )
    }

    fun tellPage() {
        val json = currentReportJson() ?: return
        sendReportToPage(json)
    }

    fun playDownloads(tracks: List<BridgeTrack>, at: Int) {
        if (destroyed || tracks.isEmpty()) return
        stopNative()
        sendRunInPage(Bridge.command("pause"))
        val settings = Settings(this)
        val state = PageState(tracks, at.coerceIn(tracks.indices), 0.0, null, true, 1.0, "off")
        start(state, settings.server ?: OFFLINE, settings.token.orEmpty(), fromPage = false)
    }

    private fun playFromCar(items: List<MediaItem>, index: Int, positionMs: Long) {
        if (destroyed) return
        val tracks = items.mapNotNull { Car.trackFrom(it.mediaMetadata.extras?.getString(CarLibrary.TRACK)) }
        val at = index.coerceAtLeast(0)
        if (tracks.isEmpty() || tracks.size != items.size || at !in tracks.indices) return
        val time = positionMs.coerceAtLeast(0) / 1000.0
        native?.let {
            it.replace(tracks, at, time, true)
            return
        }
        val settings = Settings(this)
        val server = settings.server ?: OFFLINE
        val token = settings.token.orEmpty()
        val volume = pagePlayer.shown?.volume ?: DEFAULT_VOLUME
        start(PageState(tracks, at, time, null, true, volume, "off"), server, token, fromPage = true).replaced = true
    }

    private fun start(
        state: PageState,
        server: String,
        token: String,
        fromPage: Boolean,
        catchUp: Boolean = false,
        playWhenReady: Boolean = true,
    ): Native {
        val renderersFactory = RenderersFactory { eventHandler, _, audioListener, _, _ ->
            arrayOf(
                object : MediaCodecAudioRenderer(
                    this,
                    MediaCodecSelector.DEFAULT,
                    true,
                    eventHandler,
                    audioListener,
                    SpectrumSink(DefaultAudioSink.Builder(this).build(), spectrum),
                ) {
                    override fun getDurationToProgressUs(
                        positionUs: Long,
                        elapsedRealtimeUs: Long,
                        isOnBufferAvailableListenerRegistered: Boolean,
                    ): Long {
                        val wait = super.getDurationToProgressUs(
                            positionUs,
                            elapsedRealtimeUs,
                            isOnBufferAvailableListenerRegistered,
                        )
                        return if (getState() == Renderer.STATE_STARTED) spectrum.wakeWithinUs(wait) else wait
                    }
                },
            )
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
            .setTargetBufferBytes(EXO_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(Downloads.playing(this)))
            .setAudioAttributes(MUSIC, !catchUp)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setMediaItems(itemsFor(server, state.tracks), state.position, (state.time * 1000).toLong())
        player.repeatMode = Bridge.repeatMode(state.repeat)
        val running = Native(player, state, server, token, fromPage)
        native = running
        player.addListener(running)
        player.volume = if (catchUp) 0f else running.volumeAt(state.position)
        if (!fromPage) session.player = player
        player.prepare()
        if (Settings(this).shuffle) running.setShuffle(true)
        running.syncing = catchUp
        running.since = SystemClock.elapsedRealtime()
        player.playWhenReady = playWhenReady
        if (fromPage) main.postDelayed({ if (native === running && !running.live) giveUp(running) }, TAKE_OVER_WITHIN_MS)
        return running
    }

    @Volatile
    var handingBack = false
        private set

    fun handBackState(): HandBack? {
        if (handingBack) return null
        val running = native?.takeIf { it.live } ?: return null
        val player = running.player
        return HandBack(
            player.currentMediaItemIndex,
            player.currentPosition / 1000.0,
            player.playWhenReady,
            running.tracks.takeIf { running.replaced },
            System.currentTimeMillis().takeIf { player.isPlaying },
        )
    }

    fun startHandBack() {
        handingBack = true
    }

    fun abandonHandBack() {
        handingBack = false
    }

    fun finishHandBack() {
        stopNative()
    }

    private fun itemsFor(server: String, tracks: List<BridgeTrack>) =
        tracks.map { CarLibrary.playableItem(this, server, it) }

    private fun giveUp(running: Native) {
        if (native !== running) return
        stopNative()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playingNatively || native?.player?.playWhenReady != true) {
            if (::pagePlayer.isInitialized) pagePlayer.show(null, "")
            stopSelf()
        }
    }

    override fun onDestroy() {
        destroyed = true
        spectrum.on = false
        main.removeCallbacksAndMessages(null)
        val running = native
        native = null
        running?.release(notify = false)
        discord?.stop()
        discord = null
        if (::session.isInitialized) session.release()
        if (::library.isInitialized) library.release()
        if (::pagePlayer.isInitialized) pagePlayer.release()
        reporter.shutdown()
        if (Playback.service === this) Playback.service = null
        super.onDestroy()
    }

    private fun withTokenForOwnOrigin(): DataSource.Factory {
        val app = applicationContext
        val http = DefaultHttpDataSource.Factory().setUserAgent(Net.userAgent)
        return ResolvingDataSource.Factory(http) { dataSpec ->
            val settings = Settings(app)
            val token = settings.token
            val expectedOrigin = settings.server?.let(Server::origin)
            val requestOrigin = Server.origin(dataSpec.uri.toString())
            if (!token.isNullOrEmpty() && expectedOrigin != null && requestOrigin == expectedOrigin) {
                val headers = HashMap(dataSpec.httpRequestHeaders)
                headers["Authorization"] = "Token $token"
                dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
            } else {
                dataSpec
            }
        }
    }

    private fun onReporter(work: () -> Unit) {
        if (destroyed) return
        try {
            reporter.execute(work)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun report(kind: String, track: BridgeTrack, server: String, token: String) {
        val app = applicationContext
        val playedAt = System.currentTimeMillis() / 1000
        onReporter {
            val sent = token.isNotEmpty() && server != OFFLINE && runCatching {
                val body = JSONObject().put("trackId", track.trackId)
                track.songId?.let { body.put("songId", it) }
                val status = Net.postJson(
                    address = "$server/api/$kind",
                    body = body.toString(),
                    token = token,
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 10_000,
                    readResponse = false,
                ).status
                status in 200..299 || status == 404
            }.getOrDefault(false)
            if (kind != "play") return@onReporter
            if (sent) {
                OfflinePlays.send(app)
            } else {
                OfflinePlays.add(app, track, playedAt)
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
        @Volatile
        var live = false
        var syncing = false
        var since = 0L
        private var pageTime = state.time
        private var refined = false
        private var refining = false
        private var settledSince: Long? = null
        var tracks = state.tracks
        var replaced = false
        private var volume = state.volume
        private var link: RemoteLink? = null
        private var heard = 0.0
        private var before: Double? = null
        private var counted = false
        private var announced = -1
        private var refilledAfter: String? = null
        private var refillingFor: String? = null
        private var refillRetryAt = 0L
        private var unshuffled: List<BridgeTrack>? = null
        private var mutatingTimeline = false
        private var consecutiveErrors = 0
        private val tick = object : Runnable {
            override fun run() {
                if (this@PlaybackService.native !== this@Native) return
                follow()
                keepGoing()
                updateDiscord()
                if (player.isPlaying) {
                    main.postDelayed(this, TICK_MS)
                }
            }
        }

        private fun keepGoing() {
            val index = player.currentMediaItemIndex
            if (index !in tracks.indices) return
            val left = player.mediaItemCount - index - 1
            val autoplay = Settings(this@PlaybackService).autoplay
            if (!Radio.needsMore(autoplay, player.repeatMode == Player.REPEAT_MODE_OFF, left)) return
            val seed = tracks.getOrNull(index) ?: return
            val now = SystemClock.elapsedRealtime()
            if (seed.trackId == refilledAfter ||
                seed.trackId == refillingFor ||
                now < refillRetryAt ||
                token.isEmpty() ||
                server == OFFLINE
            ) {
                return
            }
            refillingFor = seed.trackId
            val exclude = tracks.subList(index, tracks.size).mapNotNull { it.songId }
            val skipStart = (tracks.size - Radio.REMEMBERS).coerceAtLeast(0)
            val skipIds = tracks.subList(skipStart, tracks.size).map { it.trackId }
            val body = Radio.body(seed, exclude, skipIds)
            onReporter {
                val songs = runCatching { Radio.fetch(server, token, body) }.getOrNull()
                main.post {
                    if (this@PlaybackService.native !== this@Native) return@post
                    if (refillingFor == seed.trackId) refillingFor = null
                    if (songs.isNullOrEmpty()) {
                        refillRetryAt = SystemClock.elapsedRealtime() + REFILL_RETRY_MS
                        return@post
                    }
                    if (tracks.getOrNull(player.currentMediaItemIndex)?.trackId != seed.trackId) return@post
                    refilledAfter = seed.trackId
                    refillRetryAt = 0L
                    enqueueAll(songs)
                    changed()
                }
            }
        }

        private fun enqueueAll(songs: List<BridgeTrack>) {
            if (songs.isEmpty()) return
            val updated = ArrayList<BridgeTrack>(tracks.size + songs.size).apply {
                addAll(tracks)
                addAll(songs)
            }
            tracks = updated
            unshuffled = unshuffled?.let { it + songs }
            replaced = true
            mutatingTimeline = true
            try {
                player.addMediaItems(itemsFor(server, songs))
            } finally {
                mutatingTimeline = false
            }
            pruneHistory()
        }

        private fun pruneHistory() {
            val index = player.currentMediaItemIndex
            if (index <= MAX_HISTORY) return
            val drop = minOf(index - MAX_HISTORY, tracks.size, player.mediaItemCount)
            if (drop <= 0) return
            tracks = tracks.subList(drop, tracks.size).toList()
            announced = if (announced >= drop) announced - drop else -1
            mutatingTimeline = true
            try {
                player.removeMediaItems(0, drop)
            } finally {
                mutatingTimeline = false
            }
        }

        private var syncStarted = 0L
        private val sync = object : Runnable {
            override fun run() {
                keepInStep()
            }
        }

        private fun keepInStep() {
            if (!syncing || this@PlaybackService.native !== this) return
            val now = SystemClock.elapsedRealtime()
            if (player.currentMediaItemIndex != state.position || now - syncStarted > SYNC_WITHIN_MS) {
                finishSync()
                return
            }
            val known = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000.0 }
            val page = Bridge.catchUp(pageTime, now - since, known ?: state.current.duration)
            val behind = page - player.currentPosition / 1000.0
            when {
                Bridge.jumpNeeded(behind) -> {
                    player.seekTo(player.currentMediaItemIndex, (page * 1000).toLong())
                    settledSince = null
                }
                Bridge.inStep(behind) -> {
                    val settled = settledSince
                    when {
                        player.playbackParameters.speed != 1f -> {
                            player.playbackParameters = PlaybackParameters.DEFAULT
                            settledSince = now
                        }
                        settled == null -> settledSince = now
                        !refined -> askPageAgain()
                        now - settled >= SETTLE_MS -> {
                            finishSync()
                            return
                        }
                    }
                }
                else -> {
                    player.playbackParameters = PlaybackParameters(Bridge.nudge(behind))
                    settledSince = null
                }
            }
            main.postDelayed(sync, SYNC_STEP_MS)
        }

        private fun askPageAgain() {
            if (refining) return
            refining = true
            val asked = SystemClock.elapsedRealtime()
            requestReadPage { fresh ->
                if (this@PlaybackService.native === this && syncing) {
                    val answered = SystemClock.elapsedRealtime()
                    if (fresh != null &&
                        fresh.playing &&
                        fresh.current.trackId == state.current.trackId &&
                        answered - asked <= TRUSTED_READ_MS
                    ) {
                        pageTime = fresh.time
                        since = Bridge.readMoment(asked, answered)
                    }
                    refined = true
                    refining = false
                }
            }
        }

        private fun finishSync() {
            syncing = false
            main.removeCallbacks(sync)
            player.playbackParameters = PlaybackParameters.DEFAULT
            player.volume = volumeAt(player.currentMediaItemIndex)
            player.setAudioAttributes(MUSIC, true)
            goLive()
        }

        private fun goLive() {
            if (live || this@PlaybackService.native !== this) return
            live = true
            session.player = player
            if (fromPage) sendRunInPage(Bridge.command("pause"))
            sendRunInPage(Remote.holdScript(true))
            sendNativeState()
            connect()
            announce()
            updateDiscord()
            main.post(tick)
        }

        fun volumeAt(index: Int) = (volume * (tracks.getOrNull(index)?.level ?: 1.0)).toFloat()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0
            changed()
            if (live) {
                main.removeCallbacks(tick)
                if (isPlaying) {
                    main.post(tick)
                } else {
                    before = null
                    updateDiscord()
                }
                return
            }
            if (!isPlaying) return
            if (!syncing) {
                goLive()
                return
            }
            if (syncStarted == 0L) {
                syncStarted = SystemClock.elapsedRealtime()
                main.post(sync)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            pruneHistory()
            player.volume = volumeAt(player.currentMediaItemIndex)
            heard = 0.0
            before = null
            counted = false
            if (live) announce()
            changed()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            changed()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            changed()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            changed()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (mutatingTimeline || reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            changed()
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
            unshuffled = null
            refilledAfter = null
            refillingFor = null
            refillRetryAt = 0L
            consecutiveErrors = 0
            mutatingTimeline = true
            try {
                player.setMediaItems(itemsFor(server, next), position, (time * 1000).toLong())
            } finally {
                mutatingTimeline = false
            }
            player.volume = volumeAt(position)
            player.prepare()
            player.playWhenReady = playing
            if (Settings(this@PlaybackService).shuffle) setShuffle(true)
        }

        fun setShuffle(on: Boolean) {
            val from = player.currentMediaItemIndex + 1
            if (from > tracks.size) return
            val original = unshuffled
            val wanted = when {
                on && original == null -> {
                    val upcoming = tracks.subList(from, tracks.size).toList()
                    unshuffled = upcoming
                    Shuffle.shuffled(upcoming)
                }
                !on && original != null -> {
                    unshuffled = null
                    Shuffle.restored(original, tracks.subList(from, tracks.size))
                }
                else -> return
            }
            val order = tracks.toMutableList()
            val moves = Shuffle.moves(order, wanted, from)
            mutatingTimeline = true
            try {
                for ((at, to) in moves) {
                    order.add(to, order.removeAt(at))
                    player.moveMediaItem(at, to)
                }
            } finally {
                mutatingTimeline = false
            }
            tracks = order
            replaced = true
            changed()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!shuffleModeEnabled) return
            player.shuffleModeEnabled = false
            val on = unshuffled == null
            Settings(this@PlaybackService).shuffle = on
            setShuffle(on)
        }

        fun obeyHere(command: RemoteCommand) {
            obey(command)
            changed()
        }

        fun report(): String =
            Remote.report(Settings(this@PlaybackService).deviceId, Remote.deviceName(Build.MODEL), snapshot())

        fun discordSource(): DiscordSource? {
            val snap = snapshot()
            val track = snap.track ?: return null
            if (!snap.playing) return null
            return DiscordSource(
                playing = DiscordPlaying(
                    song = DiscordSong(track.title, track.artist, track.album),
                    position = snap.time,
                    length = snap.duration,
                    art = track.cover,
                ),
                server = server,
                token = token,
            )
        }

        private fun changed() {
            if (this@PlaybackService.native !== this) return
            link?.reportSoon()
            sendReportToPage(report())
            if (live) updateDiscord()
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
            val repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ALL -> "all"
                Player.REPEAT_MODE_ONE -> "one"
                else -> "off"
            }
            val upNext = if (index >= 0 && index + 1 < tracks.size) {
                tracks.subList(index + 1, minOf(tracks.size, index + 1 + Remote.MOST_UP_NEXT)).toList()
            } else {
                emptyList()
            }
            return RemoteSnapshot(
                track,
                going,
                player.currentPosition / 1000.0,
                length,
                volume,
                repeat,
                upNext,
                unshuffled != null,
            )
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
                is RemoteCommand.Repeat -> player.repeatMode = Bridge.repeatMode(command.mode)
                is RemoteCommand.Shuffle -> {
                    Settings(this@PlaybackService).shuffle = command.on
                    setShuffle(command.on)
                }
                is RemoteCommand.Jump -> {
                    val target = player.currentMediaItemIndex + command.ahead
                    if (target < player.mediaItemCount) player.seekTo(target, 0)
                }
                is RemoteCommand.Enqueue -> {
                    val count = player.mediaItemCount
                    val at = if (command.next) (player.currentMediaItemIndex + 1).coerceIn(0, count) else count
                    tracks = tracks.toMutableList().apply { add(at.coerceAtMost(size), command.track) }
                    unshuffled = unshuffled?.let { if (command.next) listOf(command.track) + it else it + command.track }
                    replaced = true
                    mutatingTimeline = true
                    try {
                        player.addMediaItem(at, CarLibrary.playableItem(this@PlaybackService, server, command.track))
                    } finally {
                        mutatingTimeline = false
                    }
                }
                is RemoteCommand.Send -> {
                    val body = Remote.send(
                        command.to,
                        tracks,
                        player.currentMediaItemIndex,
                        player.currentPosition / 1000.0,
                    ) ?: return
                    link?.send(body) { sent ->
                        if (sent && this@PlaybackService.native === this) player.pause()
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!live && fromPage) {
                giveUp(this)
                return
            }
            if (player.hasNextMediaItem() && consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                consecutiveErrors++
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            } else {
                player.pause()
                changed()
            }
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

        fun release(notify: Boolean = true) {
            link?.stop()
            link = null
            if (live) sendRunInPage(Remote.holdScript(false))
            main.removeCallbacks(tick)
            main.removeCallbacks(sync)
            player.removeListener(this)
            player.release()
            if (notify && !destroyed) {
                main.post {
                    if (destroyed) return@post
                    tellPage()
                    updateDiscord()
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1000L
        const val SPECTRUM_TICK_MS = 16L
        const val TAKE_OVER_WITHIN_MS = 12_000L
        const val SYNC_WITHIN_MS = 6_000L
        const val SYNC_STEP_MS = 50L
        const val TRUSTED_READ_MS = 150L
        const val SETTLE_MS = 300L
        const val REFILL_RETRY_MS = 15_000L
        const val MAX_CONSECUTIVE_ERRORS = 3
        const val MAX_HISTORY = 100
        const val MAX_ARTWORK_DIM_PX = 512
        const val EXO_TARGET_BUFFER_BYTES = 4 * 1024 * 1024
        val MUSIC: AudioAttributes =
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build()
        const val OFFLINE = CarLibrary.OFFLINE
        const val DEFAULT_VOLUME = 0.8
    }
}
