package io.github.novakr12.soundscape

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DiscordLink(context: Context) {
    private data class CachedLooked(val looked: DiscordLooked, val retryAt: Long?)

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Pools.serial("soundscape-discord")
    private val handshakeBusy = AtomicBoolean(false)
    private val lock = Any()
    private var desired: DiscordSource? = null
    private var dirty = false
    private var scheduled = false
    private var resetBackoff = false
    private var stopped = false

    // Accessed only on the worker thread.
    private var session: RpcSession? = null
    private var shown: DiscordShown? = null
    private var nonce = 0L
    private var nextRetryAt = 0L
    private val known = HashMap<DiscordSong, CachedLooked>()
    private val idleDisconnect = Runnable {
        synchronized(lock) {
            if (stopped || desired != null) return@Runnable
        }
        runOnWorker {
            val current = synchronized(lock) { desired }
            if (current == null && shown == null) closeSession()
        }
    }

    fun update(source: DiscordSource?, force: Boolean = false) {
        val shouldSchedule = synchronized(lock) {
            if (stopped) return
            desired = source
            if (force) resetBackoff = true
            dirty = true
            if (scheduled) {
                false
            } else {
                scheduled = true
                true
            }
        }
        if (shouldSchedule) runOnWorker(::drain)
    }

    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            desired = null
            dirty = false
        }
        main.removeCallbacks(idleDisconnect)
        runOnWorker {
            clearAndClose()
        }
        worker.shutdown()
    }

    private fun runOnWorker(work: () -> Unit) {
        try {
            worker.execute(work)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun drain() {
        while (true) {
            val (source, reset) = synchronized(lock) {
                if (stopped || !dirty) {
                    scheduled = false
                    return
                }
                dirty = false
                val shouldReset = resetBackoff
                resetBackoff = false
                desired to shouldReset
            }
            if (reset) nextRetryAt = 0L
            step(source)
        }
    }

    private fun wantedAppId(): String? {
        val settings = Settings(app)
        return settings.discordAppId?.takeIf { settings.discord && Discord.validAppId(it) }
    }

    private fun step(source: DiscordSource?) {
        val appId = wantedAppId()
        if (appId == null) {
            main.removeCallbacks(idleDisconnect)
            clearAndClose()
            nextRetryAt = 0L
            return
        }
        val active = session
        if (active != null && (active.appId != appId || active.isClosed)) {
            clearAndClose()
        }
        if (source == null) {
            if (shown != null) {
                val current = session
                if (current != null && !current.isClosed) {
                    val sent = current.sendFrame(Discord.activity(null, Process.myPid(), ++nonce))
                    if (!sent) closeSession()
                }
                shown = null
            }
            if (session != null) scheduleIdleDisconnect()
            return
        }
        main.removeCallbacks(idleDisconnect)
        if (session == null) {
            val now = SystemClock.elapsedRealtime()
            if (now < nextRetryAt) return
            val opened = openSession(appId)
            if (opened == null) {
                nextRetryAt = now + Discord.RETRY_AFTER_MS
                return
            }
            session = opened
            shown = null
            nextRetryAt = 0L
        }
        val (startMs, endMs) = Discord.timing(
            System.currentTimeMillis(),
            source.playing.position,
            source.playing.length,
        )
        val found = lookUp(source)
        val next = DiscordShown(
            song = source.playing.song,
            startMs = startMs,
            endMs = endMs,
            image = found.image,
            album = Discord.albumLabel(source.playing.song.album, found.album),
        )
        if (!Discord.needsUpdate(shown, next)) return
        val frame = Discord.activity(next, Process.myPid(), ++nonce)
        val current = session ?: return
        if (current.sendFrame(frame)) {
            shown = next
        } else {
            closeSession()
            shown = null
            nextRetryAt = SystemClock.elapsedRealtime() + Discord.RETRY_AFTER_MS
        }
    }

    private fun clearAndClose() {
        val current = session
        if (current != null && !current.isClosed && shown != null) {
            current.sendFrame(Discord.activity(null, Process.myPid(), ++nonce))
        }
        shown = null
        closeSession()
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun scheduleIdleDisconnect() {
        main.removeCallbacks(idleDisconnect)
        main.postDelayed(idleDisconnect, Discord.IDLE_DISCONNECT_MS)
    }

    private fun lookUp(source: DiscordSource): DiscordLooked {
        val song = source.playing.song
        val now = SystemClock.elapsedRealtime()
        known[song]?.let { entry ->
            if (entry.retryAt == null || now < entry.retryAt) return entry.looked
        }
        val server = source.server?.takeIf { it != CarLibrary.OFFLINE }
        val art = Discord.publicArt(source.playing.art, Discord.serverHost(server))
        val pageAlbum = Discord.realAlbum(song.album)
        val needsLookup = art == null || pageAlbum == null
        var remoteUrl: String? = null
        var remoteAlbum: String? = null
        var retryAt: Long? = null
        if (needsLookup) {
            if (server != null && !source.token.isNullOrEmpty()) {
                val result = fetchCoverLink(server, source.token, song)
                if (result != null) {
                    remoteUrl = result.first
                    remoteAlbum = result.second
                } else {
                    retryAt = now + COVER_RETRY_MS
                }
            } else {
                retryAt = now + COVER_RETRY_MS
            }
        }
        val looked = DiscordLooked(
            image = art ?: remoteUrl ?: Discord.LOGO,
            album = pageAlbum ?: remoteAlbum,
        )
        if (known.size > MOST_KNOWN) known.clear()
        known[song] = CachedLooked(looked, retryAt)
        return looked
    }

    private fun fetchCoverLink(server: String, token: String, song: DiscordSong): Pair<String?, String?>? =
        runCatching {
            val answer = Net.get(
                address = Discord.coverLinkAddress(server, song.artist, song.title),
                token = token,
                connectTimeoutMs = HTTP_TIMEOUT_MS,
                readTimeoutMs = HTTP_TIMEOUT_MS,
            )
            if (answer.status !in 200..299 || answer.body == null) return@runCatching null
            Discord.parseCoverLink(answer.body)
        }.getOrNull()

    private fun resolveServiceIntent(): Intent? {
        val intent = Intent(SERVICE_ACTION).setPackage(DISCORD_PACKAGE)
        val pm = app.packageManager
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveService(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveService(intent, 0)
        }
        return intent.takeIf { resolved != null }
    }

    private fun openSession(appId: String): RpcSession? {
        val numericId = appId.toLongOrNull() ?: return null
        val intent = runCatching { resolveServiceIntent() }.getOrNull() ?: return null
        val closed = AtomicBoolean(false)
        val (serviceConnection, service) = bindDiscordService(intent, closed) ?: return null
        val connected = handshake(service, numericId, callbackBinder(closed))
        if (connected == null || closed.get()) {
            if (connected != null) disconnectBinder(connected)
            runCatching { app.unbindService(serviceConnection) }
            return null
        }
        return RpcSession(app, appId, serviceConnection, connected, closed)
    }

    private fun bindDiscordService(intent: Intent, closed: AtomicBoolean): Pair<ServiceConnection, IBinder>? {
        val boundBinder = AtomicReference<IBinder?>()
        val bindLatch = CountDownLatch(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                boundBinder.set(service)
                bindLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                closed.set(true)
            }

            override fun onBindingDied(name: ComponentName?) {
                closed.set(true)
                bindLatch.countDown()
            }

            override fun onNullBinding(name: ComponentName?) {
                closed.set(true)
                bindLatch.countDown()
            }
        }
        val bound = runCatching {
            app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            runCatching { app.unbindService(serviceConnection) }
            return null
        }
        val waited = runCatching { bindLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val service = boundBinder.get()
        if (!waited || service == null || closed.get()) {
            runCatching { app.unbindService(serviceConnection) }
            return null
        }
        return serviceConnection to service
    }

    private fun callbackBinder(closed: AtomicBoolean): Binder = object : Binder() {
        init {
            attachInterface(null, CALLBACK_DESCRIPTOR)
        }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    return true
                }
                TRANSACTION_ON_FRAME -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    handleFrame(data.readString())
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_ON_CLOSE -> {
                    data.enforceInterface(CALLBACK_DESCRIPTOR)
                    val closeCode = data.readInt()
                    val message = data.readString()
                    Log.w(TAG, "Discord RPC closed ($closeCode): ${message.orEmpty()}")
                    closed.set(true)
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun handshake(service: IBinder, appId: Long, callback: IBinder): IBinder? {
        if (!handshakeBusy.compareAndSet(false, true)) return null
        val latch = CountDownLatch(1)
        val handshakeLock = Any()
        var timedOut = false
        var connection: IBinder? = null
        try {
            Pools.io.execute {
                try {
                    val result = runCatching { connectBinder(service, appId, callback) }.getOrNull()
                    val stale = synchronized(handshakeLock) {
                        if (timedOut) {
                            true
                        } else {
                            connection = result
                            false
                        }
                    }
                    if (stale) {
                        if (result != null) disconnectBinder(result)
                    } else {
                        latch.countDown()
                    }
                } finally {
                    handshakeBusy.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            handshakeBusy.set(false)
            return null
        }
        val completed = runCatching { latch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val connected = synchronized(handshakeLock) {
            if (!completed) timedOut = true
            connection
        }
        if (!completed) {
            if (connected != null) disconnectBinder(connected)
            return null
        }
        return connected
    }

    private fun handleFrame(frame: String?) {
        if (frame.isNullOrBlank()) return
        val message = runCatching { JSONObject(frame) }.getOrNull() ?: return
        if (message.optString("evt") == "ERROR") {
            val details = message.optJSONObject("data")
            val text = details?.optString("message").orEmpty()
            Log.w(TAG, "Discord RPC error: ${text.ifEmpty { frame.take(200) }}")
        }
    }

    private class RpcSession(
        private val app: Context,
        val appId: String,
        private val serviceConnection: ServiceConnection,
        private val connection: IBinder,
        private val closed: AtomicBoolean,
    ) {
        private val released = AtomicBoolean(false)

        val isClosed: Boolean get() = closed.get() || released.get() || !connection.isBinderAlive

        fun sendFrame(frame: String): Boolean {
            if (isClosed) return false
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(CONNECTION_DESCRIPTOR)
                data.writeString(frame)
                if (!connection.transact(TRANSACTION_SEND_FRAME, data, reply, 0)) {
                    throw RemoteException("Discord rejected frame")
                }
                reply.readException()
                true
            } catch (error: Exception) {
                Log.w(TAG, "Discord RPC sendFrame failed: ${error.message}")
                closed.set(true)
                false
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        fun close() {
            if (!released.compareAndSet(false, true)) return
            closed.set(true)
            disconnectBinder(connection)
            runCatching { app.unbindService(serviceConnection) }
        }
    }

    private companion object {
        const val TAG = "DiscordLink"
        const val DISCORD_PACKAGE = "com.discord"
        const val SERVICE_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
        const val SERVICE_DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcService"
        const val CONNECTION_DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcConnection"
        const val CALLBACK_DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcCallback"
        const val RPC_VERSION = "1"
        const val TRANSACTION_CONNECT = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_SEND_FRAME = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_DISCONNECT = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_ON_FRAME = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_ON_CLOSE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val COVER_RETRY_MS = 60_000L
        const val HTTP_TIMEOUT_MS = 10_000
        const val MOST_KNOWN = 200

        fun connectBinder(service: IBinder, appId: Long, callback: IBinder): IBinder? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                data.writeLong(appId)
                data.writeString(RPC_VERSION)
                data.writeStrongBinder(callback)
                if (!service.transact(TRANSACTION_CONNECT, data, reply, 0)) return null
                reply.readException()
                reply.readStrongBinder()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        fun disconnectBinder(connection: IBinder) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(CONNECTION_DESCRIPTOR)
                connection.transact(TRANSACTION_DISCONNECT, data, reply, 0)
                reply.readException()
            } catch (_: Exception) {
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
