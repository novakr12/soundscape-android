package io.github.novakr12.soundscape

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.view.View
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
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var trouble: View
    private lateinit var server: String
    private var picking: ValueCallback<Array<Uri>>? = null
    private val main = Handler(Looper.getMainLooper())
    private var controller: ListenableFuture<MediaController>? = null
    private var visible = false
    private var settingsShown: Pair<String, String>? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            followPage()
            if (visible) main.postDelayed(this, PAGE_TICK_MS)
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
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            WindowInsetsCompat.CONSUMED
        }
        matchBarsToTheme()
        setUpWeb()
        findViewById<Button>(R.id.retry).setOnClickListener {
            trouble.visibility = View.GONE
            web.reload()
        }
        findViewById<Button>(R.id.change_server).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else moveTaskToBack(true)
            }
        })
        Playback.runInPage = { script -> web.evaluateJavascript(script, null) }
        if (Settings(this).token != null) NoticeWorker.start(this)
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(firstPage(intent))
        }
    }

    private fun firstPage(intent: Intent?): String {
        val settings = Settings(this)
        val page = intent?.takeIf { it.action == ACTION_OPEN }?.getStringExtra(EXTRA_PAGE)
        if (page != null && page.startsWith("/") && !page.startsWith("//")) return "$server$page"
        val asked = intent?.action == ACTION_CONNECT
        if (asked || (settings.token == null && !settings.askedToConnect)) {
            settings.askedToConnect = true
            return Bridge.connectAddress(server, "Android (${Build.MODEL})")
        }
        return server
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if ((intent.action == ACTION_CONNECT || intent.action == ACTION_OPEN) && ::web.isInitialized) {
            web.loadUrl(firstPage(intent))
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onStop() {
        visible = false
        main.removeCallbacks(tick)
        handOverToPhone()
        super.onStop()
    }

    private fun followPage() {
        if (!::web.isInitialized) return
        followSettingsPage()
        val service = Playback.service
        if (service?.playingNatively == true) {
            handBackToPage(service)
            return
        }
        web.evaluateJavascript(Bridge.READ) { result ->
            val state = Bridge.parse(result)
            Playback.service?.showPage(state, server)
            if (state?.playing == true) connectToService()
        }
    }

    private fun handBackToPage(service: PlaybackService) {
        val back = service.handBackState() ?: return
        web.evaluateJavascript(Bridge.handBack(back)) { result ->
            if (result == "true") service.finishHandBack()
        }
    }

    private fun handOverToPhone() {
        if (!Settings(this).backgroundPlayback) return
        val token = Settings(this).token ?: return
        val service = Playback.service ?: return
        if (service.playingNatively || !::web.isInitialized) return
        web.evaluateJavascript(Bridge.READ) { result ->
            val state = Bridge.parse(result) ?: return@evaluateJavascript
            if (state.playing && !visible) service.takeOver(state, server, token)
        }
    }

    private fun followSettingsPage() {
        web.evaluateJavascript(SettingsPage.READ) { result ->
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
        )
    }

    private fun obey(command: SettingsCommand): String? {
        val settings = Settings(this)
        when (command) {
            is SettingsCommand.Option -> when (command.name) {
                SettingsPage.OPTION_BACKGROUND -> settings.backgroundPlayback = command.value
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
            SettingsCommand.Connect -> web.loadUrl(Bridge.connectAddress(server, "Android (${Build.MODEL})"))
            SettingsCommand.Disconnect -> disconnect()
            SettingsCommand.ChangeServer -> startActivity(Intent(this, SetupActivity::class.java))
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
        worker.execute {
            runCatching {
                val connection = URL("$home/api/token").openConnection() as HttpURLConnection
                connection.requestMethod = "DELETE"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.setRequestProperty("Authorization", "Token $token")
                connection.responseCode
                connection.disconnect()
            }
        }
        NoticeWorker.stop(this)
        settings.forgetConnection()
        settings.askedToConnect = true
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
        if (controller != null) return
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWeb() {
        CookieManager.getInstance().setAcceptCookie(true)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString SoundscapeAndroid/${BuildConfig.VERSION_NAME}"
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val address = request.url.toString()
                Bridge.readToken(server, address)?.let { (token, user) ->
                    Settings(this@MainActivity).apply {
                        this.token = token
                        this.user = user
                        lastNotification = null
                    }
                    startNotifications()
                    Toast.makeText(this@MainActivity, R.string.connected, Toast.LENGTH_SHORT).show()
                    view.loadUrl(server)
                    return true
                }
                if (Server.isOwnPage(server, address)) return false
                openOutside(request.url)
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view == web) {
                    findViewById<FrameLayout>(R.id.main_root).removeView(web)
                    web.destroy()
                    recreate()
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
            val name = URLUtil.guessFileName(address, disposition, kind)
            val request = DownloadManager.Request(Uri.parse(address))
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(address))
                .addRequestHeader("User-Agent", agent)
                .setMimeType(kind)
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            getSystemService(DownloadManager::class.java).enqueue(request)
            Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openOutside(address: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, address))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchBarsToTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        matchBarsToTheme()
    }

    override fun onResume() {
        super.onResume()
        val now = Settings(this).server
        if (now != null && now != server) {
            server = now
            web.clearHistory()
            web.loadUrl(server)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::web.isInitialized) web.saveState(outState)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        Playback.runInPage = null
        Playback.service?.let { if (!it.playingNatively) it.showPage(null, server) }
        controller?.let { MediaController.releaseFuture(it) }
        worker.shutdown()
        controller = null
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val ACTION_OPEN = "io.github.novakr12.soundscape.OPEN"
        const val EXTRA_PAGE = "page"
        private const val PAGE_TICK_MS = 1000L
        private const val ACTION_CONNECT = "io.github.novakr12.soundscape.CONNECT"
    }
}
