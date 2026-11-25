package io.github.novakr12.soundscape

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.RejectedExecutionException

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private data class PendingTakeOver(
        val state: PageState,
        val server: String,
        val token: String,
        val readAt: Long,
    )

    private lateinit var web: WebView
    private lateinit var trouble: View
    private lateinit var server: String
    private var pageNative = false
    private var listening: String? = null
    private val waitingForService = mutableListOf<String>()
    private val waitingSaveQueue = mutableListOf<Pair<String, String>>()
    private var picking: ValueCallback<Array<Uri>>? = null
    private val main = Handler(Looper.getMainLooper())
    private var controller: ListenableFuture<MediaController>? = null
    private var connectedController: MediaController? = null
    private var pendingTellPage = false
    private var pendingTakeOver: PendingTakeOver? = null
    private var nativePlaying = false
    private var nativeForPage = false
    private var handingBack = false
    private var askingHandBack = false
    private var visible = false
    private var spectrumWanted = false
    private var resumed = false
    private var webPaused = false
    private var webDestroyed = false
    private var renderGonePending = false
    private var pagePlaying = false
    private var currentPageUrl: String? = null
    private var immersiveFullscreen = false
    private var settingsShown: Pair<String, String>? = null
    private var downloadsShown: Pair<String, String>? = null
    private var deviceScript: ScriptHandler? = null

    private val tick = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            followPage()
            if (visible || (!pageNative && pagePlaying)) {
                main.postDelayed(this, PAGE_TICK_MS)
            } else if (!pageNative && !nativePlaying && canPlayInBackground()) {
                main.postDelayed(this, BACKGROUND_TICK_MS)
            }
        }
    }

    private fun canPlayInBackground() = Settings(this).let { it.backgroundPlayback && it.token != null }

    private fun onWorker(work: () -> Unit) {
        try {
            Pools.io.execute(work)
        } catch (_: RejectedExecutionException) {
        }
    }

    private val askToNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { chosen ->
        picking?.onReceiveValue(chosen.toTypedArray())
        picking = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = Settings(this).server
        if (saved == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        server = saved
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.web)
        trouble = findViewById(R.id.trouble)
        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val mask = if (immersiveFullscreen) {
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout()
            } else {
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout()
            }
            val bars = insets.getInsets(mask)
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
        matchBarsToTheme()
        setUpWeb()
        findViewById<Button>(R.id.retry).setOnClickListener {
            if (!webDestroyed && ::web.isInitialized) {
                trouble.visibility = View.GONE
                web.reload()
            }
        }
        findViewById<Button>(R.id.play_downloads).setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        findViewById<Button>(R.id.change_server).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!webDestroyed && ::web.isInitialized) {
                    web.evaluateJavascript(Bridge.CLOSE_THEATER) { closed ->
                        if (isDestroyed) return@evaluateJavascript
                        if (closed == "true") return@evaluateJavascript
                        fallbackBack()
                    }
                } else {
                    fallbackBack()
                }
            }
        })
        val settings = Settings(this)
        if (settings.token != null && settings.notifications) NoticeWorker.start(this)
        val app = applicationContext
        onWorker {
            OfflinePlays.send(app)
            NoticeWorker.checkForUpdate(app, Settings(app))
        }
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(firstPage(intent))
        }
    }

    private fun firstPage(intent: Intent?): String {
        val settings = Settings(this)
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY)?.trim().orEmpty()
            return if (query.isEmpty()) "$server/music" else "$server/music?q=${Uri.encode(query)}"
        }
        val page = intent?.takeIf { it.action == ACTION_OPEN }?.getStringExtra(EXTRA_PAGE)
        if (page != null && page.startsWith("/") && !page.startsWith("//")) return "$server$page"
        if (settings.token == null && !settings.askedToConnect) {
            settings.askedToConnect = true
            return Bridge.connectAddress(server, "Android (${Build.MODEL})")
        }
        return server
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val known = intent.action == ACTION_OPEN ||
            intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
        if (known && ::web.isInitialized && !webDestroyed) {
            web.loadUrl(firstPage(intent))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        if (!webDestroyed && ::web.isInitialized) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        if (pageNative) requestTellPage()
        watchSpectrum()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (renderGonePending) {
            renderGonePending = false
            recreate()
            return
        }
        resumeWeb()
        if (webDestroyed || !::web.isInitialized) return
        val now = Settings(this).server
        if (now != null && now != server) {
            server = now
            waitingForService.clear()
            waitingSaveQueue.clear()
            pendingTellPage = false
            pendingTakeOver = null
            nativePlaying = false
            nativeForPage = false
            handingBack = false
            askingHandBack = false
            settingsShown = null
            downloadsShown = null
            if (immersiveFullscreen) setFullscreen(false)
            spectrumWanted = false
            watchSpectrum()
            AppBridgeProvider.stopNative(this)
            AppBridgeProvider.clearSavedQueue(this)
            AppBridgeProvider.clearLibrary(this)
            nameThisDevice()
            listenToPage()
            web.clearHistory()
            web.loadUrl(server)
        } else if (listenToPage()) {
            web.reload()
        }
    }

    override fun onPause() {
        resumed = false
        pauseWebIfIdle()
        super.onPause()
    }

    override fun onStop() {
        visible = false
        watchSpectrum()
        handOverToPhone()
        waiveRendererIfIdle()
        super.onStop()
    }

    private fun pauseWebIfIdle() {
        if (webPaused || isDestroyed || webDestroyed || !::web.isInitialized) return
        if (pageNative || !pagePlaying) {
            webPaused = true
            web.onPause()
            web.pauseTimers()
        }
    }

    private fun resumeWeb() {
        if (!webPaused || isDestroyed || webDestroyed || !::web.isInitialized) return
        webPaused = false
        web.onResume()
        web.resumeTimers()
    }

    private fun waiveRendererIfIdle() {
        if (isDestroyed || webDestroyed || !::web.isInitialized) return
        if (pageNative || !pagePlaying) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true)
        }
    }

    private fun followPage() {
        if (isDestroyed || webDestroyed || !::web.isInitialized) return
        if (visible) {
            followSettingsPage()
            followDownloads()
        }
        if (pageNative) return
        if (visible) {
            web.evaluateJavascript(Bridge.NATIVE_PAGE) {
                if (!isDestroyed && it == "true") pageNative = true
            }
        }
        if (connectedController == null) {
            val status = AppBridgeProvider.playbackStatus(this)
            if (status.running) {
                nativePlaying = status.playingNatively
                nativeForPage = status.playingForPage
                if (nativePlaying) connectToService()
            } else {
                nativePlaying = false
                nativeForPage = false
            }
        }
        if (nativePlaying) {
            pagePlaying = false
            if (visible && nativeForPage) handBackToPage()
            return
        }
        val asked = SystemClock.elapsedRealtime()
        web.evaluateJavascript(Bridge.READ) { result ->
            if (isDestroyed || webDestroyed) return@evaluateJavascript
            val state = Bridge.parse(result)
            val wasPlaying = pagePlaying
            pagePlaying = state?.playing == true
            connectedController?.takeIf { it.isConnected }?.let { active ->
                sendShowPage(active, state)
            }
            if (pagePlaying) connectToService()
            if (pagePlaying && state != null && state.remote == null && !visible && canPlayInBackground()) {
                Settings(this).token?.let { token -> sendTakeOver(state, server, token, asked) }
            }
            if (pagePlaying && !wasPlaying && !visible) {
                main.removeCallbacks(tick)
                main.postDelayed(tick, PAGE_TICK_MS)
            }
        }
    }

    private fun followSoon() {
        if (isDestroyed) return
        main.removeCallbacks(tick)
        main.postDelayed(tick, FOLLOW_SOON_MS)
    }

    private fun requestTellPage() {
        val active = connectedController?.takeIf { it.isConnected }
        if (active != null) {
            sendCommand(active, PlaybackBridge.CMD_TELL_PAGE)
        } else {
            pendingTellPage = true
            connectToService()
        }
    }

    private fun sendShowPage(target: MediaController, state: PageState?) {
        sendCommand(
            target,
            PlaybackBridge.CMD_SHOW_PAGE,
            Bundle().apply {
                putString(PlaybackBridge.KEY_SERVER, server)
                state?.let { putString(PlaybackBridge.KEY_STATE_JSON, PlaybackCodecs.encodePageState(it)) }
            },
        )
    }

    private fun sendTakeOver(state: PageState, server: String, token: String, readAt: Long) {
        val active = connectedController?.takeIf { it.isConnected }
        if (active == null) {
            pendingTakeOver = PendingTakeOver(state, server, token, readAt)
            connectToService()
            return
        }
        pendingTakeOver = null
        sendCommand(
            active,
            PlaybackBridge.CMD_TAKE_OVER,
            Bundle().apply {
                putString(PlaybackBridge.KEY_STATE_JSON, PlaybackCodecs.encodePageState(state))
                putString(PlaybackBridge.KEY_SERVER, server)
                putString(PlaybackBridge.KEY_TOKEN, token)
                putLong(PlaybackBridge.KEY_READ_AT, readAt)
            },
        )
    }

    private fun handBackToPage() {
        val active = connectedController?.takeIf { it.isConnected } ?: return
        if (handingBack || askingHandBack || isDestroyed || webDestroyed || !::web.isInitialized) return
        askingHandBack = true
        val future = active.sendCustomCommand(PlaybackBridge.CMD_HAND_BACK_STATE, Bundle.EMPTY)
        future.addListener({
            askingHandBack = false
            if (isDestroyed || webDestroyed || !::web.isInitialized) return@addListener
            val backJson = runCatching { future.get().extras.getString(PlaybackBridge.KEY_HAND_BACK_JSON) }.getOrNull()
            val back = PlaybackCodecs.decodeHandBack(backJson) ?: return@addListener
            web.evaluateJavascript(Bridge.handBack(back)) { result ->
                if (isDestroyed || webDestroyed || result != "true") return@evaluateJavascript
                if (back.at == null) {
                    sendCommand(active, PlaybackBridge.CMD_HAND_BACK_FINISH)
                    return@evaluateJavascript
                }
                handingBack = true
                sendCommand(active, PlaybackBridge.CMD_HAND_BACK_START)
                watchHandBack(SystemClock.elapsedRealtime())
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun watchHandBack(started: Long) {
        main.postDelayed({
            if (isDestroyed || webDestroyed || !::web.isInitialized || !handingBack) return@postDelayed
            val active = connectedController?.takeIf { it.isConnected }
            if (active == null) {
                handingBack = false
                return@postDelayed
            }
            if (!visible) {
                web.evaluateJavascript(Bridge.command("pause"), null)
                handingBack = false
                sendCommand(active, PlaybackBridge.CMD_HAND_BACK_ABANDON)
                return@postDelayed
            }
            web.evaluateJavascript(Bridge.READ) { result ->
                if (isDestroyed || webDestroyed || !handingBack) return@evaluateJavascript
                val waited = SystemClock.elapsedRealtime() - started
                if (Bridge.handBackDone(Bridge.parse(result)) || waited > HAND_BACK_WITHIN_MS) {
                    handingBack = false
                    sendCommand(active, PlaybackBridge.CMD_HAND_BACK_FINISH)
                } else {
                    watchHandBack(started)
                }
            }
        }, HAND_BACK_STEP_MS)
    }

    private fun handOverToPhone() {
        if (pageNative || isDestroyed || webDestroyed || !::web.isInitialized) return
        if (!Settings(this).backgroundPlayback) return
        val token = Settings(this).token ?: return
        if (nativePlaying) return
        if (connectedController == null && !AppBridgeProvider.playbackStatus(this).running) return
        val asked = SystemClock.elapsedRealtime()
        web.evaluateJavascript(Bridge.READ) { result ->
            if (isDestroyed || webDestroyed) return@evaluateJavascript
            val state = Bridge.parse(result) ?: return@evaluateJavascript
            if (state.playing && state.remote == null && !visible) {
                sendTakeOver(state, server, token, asked)
            }
        }
    }

    private fun followDownloads() {
        if (isDestroyed || webDestroyed || !::web.isInitialized) return
        web.evaluateJavascript(DownloadData.read()) { result ->
            if (isDestroyed || webDestroyed || !::web.isInitialized) return@evaluateJavascript
            val ask = DownloadData.parse(result) ?: return@evaluateJavascript
            for (request in ask.requests) {
                when (request.kind) {
                    "download" -> if (Settings(this).token == null) {
                        Toast.makeText(this, R.string.downloads_connect_first, Toast.LENGTH_LONG).show()
                    } else {
                        Downloads.add(this, server, request.tracks)
                    }
                    "remove" -> Downloads.remove(this, request.trackIds)
                }
            }
            val push = DownloadData.push(BuildConfig.VERSION_NAME, Downloads.states(this))
            if (ask.requests.isEmpty() && downloadsShown == ask.page to push) return@evaluateJavascript
            downloadsShown = ask.page to push
            web.evaluateJavascript(push, null)
        }
    }

    private fun followSettingsPage() {
        if (isDestroyed || webDestroyed || !::web.isInitialized) return
        val path = (currentPageUrl ?: web.url)?.let { runCatching { URI(it).rawPath }.getOrNull() }
        val expected = "${Server.basePath(server)}/settings"
        if (path != expected) {
            settingsShown = null
            return
        }
        web.evaluateJavascript(SettingsPage.read(server)) { result ->
            if (isDestroyed || webDestroyed || !::web.isInitialized) return@evaluateJavascript
            val ask = SettingsPage.parse(result)
            if (ask == null) {
                settingsShown = null
                return@evaluateJavascript
            }
            var error: String? = null
            for (command in ask.commands) error = obey(command) ?: error
            val payload = SettingsPage.payload(settingsView(), error)
            if (error == null && settingsShown == ask.page to payload) return@evaluateJavascript
            settingsShown = ask.page to payload
            web.evaluateJavascript(SettingsPage.pushScript(payload), null)
        }
    }

    private fun settingsView(): AppSettingsView {
        val settings = Settings(this)
        return AppSettingsView(
            version = BuildConfig.VERSION_NAME,
            connected = settings.token != null,
            user = settings.user,
            backgroundPlayback = settings.backgroundPlayback,
            notifications = settings.notifications,
            notificationsAllowed = NoticeWorker.allowed(this),
            downloadsWifiOnly = settings.downloadsWifiOnly,
            downloads = Downloads.summary(this),
            weeklyRecap = settings.weeklyRecap,
            streakReminder = settings.streakReminder,
            updateNotice = settings.updateNotice,
            update = settings.updateAvailable?.takeIf { Extras.newer(BuildConfig.VERSION_NAME, it) },
            discord = settings.discord,
            discordAppId = settings.discordAppId,
        )
    }

    private fun obey(command: SettingsCommand): String? {
        val settings = Settings(this)
        when (command) {
            is SettingsCommand.Option -> when (command.name) {
                SettingsPage.OPTION_BACKGROUND -> settings.backgroundPlayback = command.value
                SettingsPage.OPTION_WIFI -> Downloads.setWifiOnly(this, command.value)
                SettingsPage.OPTION_RECAP -> settings.weeklyRecap = command.value
                SettingsPage.OPTION_STREAK -> settings.streakReminder = command.value
                SettingsPage.OPTION_UPDATE -> {
                    settings.updateNotice = command.value
                    if (command.value) {
                        settings.updateCheckedAt = 0
                        val app = applicationContext
                        onWorker { NoticeWorker.checkForUpdate(app, Settings(app)) }
                    }
                }
                SettingsPage.OPTION_DISCORD -> {
                    settings.discord = command.value
                    AppBridgeProvider.refreshDiscord(this)
                }
                SettingsPage.OPTION_NOTIFICATIONS -> {
                    settings.notifications = command.value
                    if (!command.value) {
                        NoticeWorker.stop(this)
                    } else if (settings.token != null) {
                        settings.askedForNotifications = false
                        startNotifications()
                    }
                }
            }
            is SettingsCommand.DiscordApp -> {
                val id = command.id.trim()
                if (id.isNotEmpty() && !Discord.validAppId(id)) {
                    return getString(R.string.discord_bad_app_id)
                }
                settings.discordAppId = id.ifEmpty { null }
                AppBridgeProvider.refreshDiscord(this)
            }
            SettingsCommand.Connect -> web.loadUrl(Bridge.connectAddress(server, "Android (${Build.MODEL})"))
            SettingsCommand.Disconnect -> disconnect()
            SettingsCommand.ChangeServer -> startActivity(Intent(this, SetupActivity::class.java))
            SettingsCommand.OpenDownloads -> startActivity(Intent(this, DownloadsActivity::class.java))
            SettingsCommand.DownloadUpdate -> try {
                startActivity(Intent(Intent.ACTION_VIEW, Extras.RELEASES.toUri()))
            } catch (_: ActivityNotFoundException) {
                return getString(R.string.no_browser)
            }
            SettingsCommand.NotificationSettings -> {
                val open = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                try {
                    startActivity(open)
                } catch (_: ActivityNotFoundException) {
                    return getString(R.string.no_notification_settings)
                }
            }
        }
        return null
    }

    private fun disconnect() {
        val settings = Settings(this)
        val token = settings.token ?: return
        val home = server
        onWorker {
            runCatching { Net.delete("$home/api/token", token = token) }
        }
        NoticeWorker.stop(this)
        settings.forgetConnection()
        settings.askedToConnect = true
        waitingForService.clear()
        waitingSaveQueue.clear()
        pendingTellPage = false
        pendingTakeOver = null
        nativePlaying = false
        nativeForPage = false
        handingBack = false
        askingHandBack = false
        if (immersiveFullscreen) setFullscreen(false)
        spectrumWanted = false
        watchSpectrum()
        AppBridgeProvider.stopNative(this)
        AppBridgeProvider.clearSavedQueue(this)
        AppBridgeProvider.clearLibrary(this)
        AppBridgeProvider.refreshDiscord(this)
        if (!webDestroyed && ::web.isInitialized) {
            nameThisDevice()
            listenToPage()
        }
    }

    private fun startNotifications() {
        NoticeWorker.start(this)
        NoticeWorker.checkNow(this)
        val settings = Settings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !NoticeWorker.allowed(this) &&
            !settings.askedForNotifications
        ) {
            settings.askedForNotifications = true
            askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun connectToService() {
        if (controller != null || isDestroyed) return
        val hints = Bundle().apply { putBoolean(PlaybackBridge.HINT_PAGE_CLIENT, true) }
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setConnectionHints(hints)
            .setListener(object : MediaController.Listener {
                override fun onCustomCommand(
                    controller: MediaController,
                    command: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> = handleServiceEvent(command, args)

                override fun onDisconnected(c: MediaController) {
                    if (connectedController === c) connectedController = null
                    controller = null
                    nativePlaying = false
                    nativeForPage = false
                    handingBack = false
                    askingHandBack = false
                }
            })
            .buildAsync()
        controller = future
        future.addListener({
            if (isDestroyed) return@addListener
            val built = runCatching { future.get() }.getOrNull()
            if (built == null || !built.isConnected) {
                if (controller === future) controller = null
                connectedController = null
                return@addListener
            }
            connectedController = built
            flushWaitingCommands(built)
            watchSpectrum()
            pendingTakeOver?.let { queued ->
                pendingTakeOver = null
                if (!visible && !pageNative && !nativePlaying) {
                    sendTakeOver(queued.state, queued.server, queued.token, queued.readAt)
                }
            }
            if (pendingTellPage) {
                pendingTellPage = false
                sendCommand(built, PlaybackBridge.CMD_TELL_PAGE)
            }
            followSoon()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun flushWaitingCommands(target: MediaController) {
        if (waitingForService.isNotEmpty()) {
            val waiting = waitingForService.toList()
            waitingForService.clear()
            waiting.forEach { json ->
                sendCommand(
                    target,
                    PlaybackBridge.CMD_OBEY_PAGE,
                    Bundle().apply { putString(PlaybackBridge.KEY_COMMAND_JSON, json) },
                )
            }
        }
        if (waitingSaveQueue.isNotEmpty()) {
            val saves = waitingSaveQueue.toList()
            waitingSaveQueue.clear()
            saves.forEach { (requestId, name) -> sendSaveQueue(target, requestId, name) }
        }
    }

    private fun sendSaveQueue(target: MediaController, requestId: String, name: String) {
        sendCommand(
            target,
            PlaybackBridge.CMD_SAVE_QUEUE,
            Bundle().apply {
                putString(PlaybackBridge.KEY_REQUEST_ID, requestId)
                putString(PlaybackBridge.KEY_NAME, name)
            },
        )
    }

    private fun fallbackBack() {
        if (immersiveFullscreen) {
            setFullscreen(false)
        } else if (!webDestroyed && ::web.isInitialized && web.canGoBack()) {
            web.goBack()
        } else {
            moveTaskToBack(true)
        }
    }

    private fun setFullscreen(on: Boolean?) {
        val next = on ?: !immersiveFullscreen
        immersiveFullscreen = next
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (next) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        findViewById<View>(R.id.main_root)?.let(ViewCompat::requestApplyInsets)
        if (!webDestroyed && ::web.isInitialized) {
            web.evaluateJavascript(Bridge.fullscreenState(next), null)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && immersiveFullscreen) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun sendCommand(target: MediaController, command: SessionCommand, args: Bundle = Bundle.EMPTY) {
        target.sendCustomCommand(command, args)
    }

    private fun watchSpectrum() {
        val active = connectedController?.takeIf { it.isConnected } ?: return
        sendCommand(
            active,
            PlaybackBridge.CMD_WATCH_SPECTRUM,
            Bundle().apply { putBoolean(PlaybackBridge.KEY_ON, visible && spectrumWanted) },
        )
    }

    private fun handleServiceEvent(command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
        return when (command.customAction) {
            PlaybackBridge.ACTION_RUN_IN_PAGE -> {
                val script = args.getString(PlaybackBridge.KEY_SCRIPT)
                if (!script.isNullOrEmpty() && !isDestroyed && !webDestroyed && ::web.isInitialized) {
                    web.evaluateJavascript(script, null)
                    followSoon()
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            PlaybackBridge.ACTION_REPORT_TO_PAGE -> {
                val json = args.getString(PlaybackBridge.KEY_REPORT_JSON)
                if (!json.isNullOrEmpty() && !isDestroyed && !webDestroyed && ::web.isInitialized && visible) {
                    web.evaluateJavascript(Bridge.nativeReport(json), null)
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            PlaybackBridge.ACTION_SPECTRUM -> {
                val bins = args.getByteArray(PlaybackBridge.KEY_BINS)
                if (bins != null && visible && !isDestroyed && !webDestroyed && ::web.isInitialized) {
                    web.evaluateJavascript(Bridge.spectrum(bins), null)
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            PlaybackBridge.ACTION_READ_PAGE -> {
                val future = SettableFuture.create<SessionResult>()
                if (isDestroyed || webDestroyed || !::web.isInitialized) {
                    future.set(SessionResult(SessionResult.RESULT_SUCCESS, Bundle()))
                } else {
                    web.evaluateJavascript(Bridge.READ) { raw ->
                        future.set(
                            SessionResult(
                                SessionResult.RESULT_SUCCESS,
                                Bundle().apply { putString(PlaybackBridge.KEY_STATE_JSON, raw) },
                            ),
                        )
                    }
                }
                future
            }
            PlaybackBridge.ACTION_NATIVE_STATE -> {
                nativePlaying = args.getBoolean(PlaybackBridge.KEY_PLAYING_NATIVELY, false)
                nativeForPage = args.getBoolean(PlaybackBridge.KEY_PLAYING_FOR_PAGE, false)
                if (nativePlaying) {
                    pagePlaying = false
                    if ((!resumed || !visible) && !isDestroyed && !webDestroyed && ::web.isInitialized) {
                        web.evaluateJavascript("void 0") {
                            if (nativePlaying && !isDestroyed && !webDestroyed) {
                                if (!resumed) pauseWebIfIdle()
                                if (!visible) waiveRendererIfIdle()
                            }
                        }
                    }
                }
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString ${Net.userAgent}"
        }
        nameThisDevice()
        listenToPage()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val address = request.url.toString()
                val currentUrl = currentPageUrl ?: view.url
                if (request.isForMainFrame && (currentUrl == null || Server.isOwnPage(server, currentUrl))) {
                    Bridge.readToken(server, address)?.let { (token, user) ->
                        val settings = Settings(this@PlayerActivity).apply {
                            this.token = token
                            this.user = user
                            lastNotification = null
                        }
                        if (settings.notifications) startNotifications()
                        nameThisDevice()
                        listenToPage()
                        Toast.makeText(this@PlayerActivity, R.string.connected, Toast.LENGTH_SHORT).show()
                        view.loadUrl(server)
                        return true
                    }
                }
                if (Server.isOwnPage(server, address)) return false
                openOutside(request.url)
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (!webDestroyed && view === web && url != null) {
                    currentPageUrl = url
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!webDestroyed && view === web) {
                    currentPageUrl = url
                }
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view == web && !webDestroyed) {
                    tearDownWeb()
                    if (resumed) {
                        recreate()
                    } else {
                        renderGonePending = true
                    }
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                findViewById<TextView>(R.id.trouble_detail).text =
                    getString(R.string.trouble_detail, server, error.description)
                trouble.visibility = View.VISIBLE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams,
            ): Boolean {
                picking?.onReceiveValue(null)
                picking = callback
                val kind = params.acceptTypes.firstOrNull { it.isNotBlank() && !it.startsWith(".") } ?: "*/*"
                return try {
                    pickFiles.launch(kind)
                    true
                } catch (_: ActivityNotFoundException) {
                    picking = null
                    false
                }
            }
        }
        web.setDownloadListener { address, agent, disposition, kind, _ ->
            val uri = runCatching { address.toUri() }.getOrNull() ?: return@setDownloadListener
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return@setDownloadListener
            val name = URLUtil.guessFileName(address, disposition, kind)
            val request = DownloadManager.Request(uri)
                .setMimeType(kind)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            CookieManager.getInstance().getCookie(address)?.takeIf { it.isNotEmpty() }?.let {
                request.addRequestHeader("Cookie", it)
            }
            if (!agent.isNullOrBlank()) request.addRequestHeader("User-Agent", agent)
            val queued = runCatching {
                getSystemService(DownloadManager::class.java).enqueue(request)
                true
            }.getOrDefault(false)
            if (queued) Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOutside(address: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, address))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        matchBarsToTheme()
    }

    private fun listenToPage(): Boolean {
        if (webDestroyed || !::web.isInitialized) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val wanted = if (Settings(this).token != null) Bridge.originRule(server) else null
        if (wanted == listening) return false
        if (listening != null) WebViewCompat.removeWebMessageListener(web, NATIVE_CHANNEL)
        if (wanted != null) {
            WebViewCompat.addWebMessageListener(web, NATIVE_CHANNEL, setOf(wanted)) { _, message, _, isMainFrame, _ ->
                if (isMainFrame) fromPage(message.data)
            }
        }
        listening = wanted
        pageNative = false
        return true
    }

    private fun fromPage(text: String?) {
        if (isDestroyed || text == null) return
        val item = runCatching { JSONObject(text) }.getOrNull() ?: return
        pageNative = true
        if (item.optString("do") == "hello") {
            requestTellPage()
            return
        }
        if (item.optString("do") == "autoplay") {
            Settings(this).autoplay = item.optBoolean("on")
            return
        }
        if (item.optString("do") == "icon") {
            LauncherIcon.show(this, item.optString("accent").takeIf { item.has("accent") && !item.isNull("accent") })
            return
        }
        if (item.optString("do") == "visualizer") {
            spectrumWanted = item.optBoolean("on")
            watchSpectrum()
            return
        }
        if (item.optString("do") == "equalizer") {
            val state = Equalizer.decodeObject(item) ?: return
            Settings(this).equalizer = state
            AppBridgeProvider.applyEqualizer(this)
            return
        }
        if (item.optString("do") == "fullscreen") {
            val on = if (item.has("on") && !item.isNull("on") && item.opt("on") is Boolean) {
                item.optBoolean("on")
            } else {
                null
            }
            setFullscreen(on)
            return
        }
        if (item.optString("do") == "savequeue") {
            val requestId = item.optString("id").takeIf { it.isNotEmpty() } ?: return
            val name = item.optString("name")
            val active = connectedController?.takeIf { it.isConnected }
            if (active != null) {
                sendSaveQueue(active, requestId, name)
            } else {
                waitingSaveQueue.add(requestId to name)
                connectToService()
            }
            return
        }
        Remote.command(item) ?: return
        val active = connectedController?.takeIf { it.isConnected }
        if (active != null) {
            sendCommand(
                active,
                PlaybackBridge.CMD_OBEY_PAGE,
                Bundle().apply { putString(PlaybackBridge.KEY_COMMAND_JSON, text) },
            )
            return
        }
        waitingForService.add(text)
        connectToService()
    }

    private fun nameThisDevice() {
        deviceScript?.remove()
        deviceScript = null
        if (webDestroyed || !::web.isInitialized) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val origin = Server.origin(server) ?: return
        val settings = Settings(this)
        val script = Remote.pageScript(
            settings.deviceId,
            Remote.deviceName(Build.MODEL),
            nativeReady = settings.token != null,
        )
        deviceScript = WebViewCompat.addDocumentStartJavaScript(web, script, setOf(origin))
    }

    private fun tearDownWeb() {
        if (webDestroyed || !::web.isInitialized) return
        webDestroyed = true
        deviceScript?.remove()
        deviceScript = null
        if (listening != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(web, NATIVE_CHANNEL) }
            listening = null
        }
        web.webChromeClient = null
        web.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail) = true
        }
        web.setDownloadListener(null)
        web.stopLoading()
        web.loadUrl("about:blank")
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webDestroyed && ::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        waitingForService.clear()
        waitingSaveQueue.clear()
        pendingTakeOver = null
        if (!nativePlaying && ::server.isInitialized) {
            AppBridgeProvider.clearPageIfIdle(this, server)
        }
        connectedController = null
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        tearDownWeb()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "io.github.novakr12.soundscape.OPEN"
        const val EXTRA_PAGE = "page"
        private const val PAGE_TICK_MS = 1000L
        private const val HAND_BACK_STEP_MS = 50L
        private const val NATIVE_CHANNEL = "soundscapeNative"
        private const val HAND_BACK_WITHIN_MS = 7000L
        private const val FOLLOW_SOON_MS = 500L
        private const val BACKGROUND_TICK_MS = 5000L
    }
}
