package io.github.novakr12.soundscape

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.util.concurrent.RejectedExecutionException
import kotlin.concurrent.thread

class RemoteLink(
    private val server: String,
    private val token: String,
    private val id: String,
    private val name: String,
    private val snapshot: () -> RemoteSnapshot,
    private val obey: (RemoteCommand) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val posts = Pools.serial("soundscape-remote-posts")

    @Volatile
    private var running = false

    @Volatile
    private var connection: HttpURLConnection? = null
    private var listener: Thread? = null

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            reportNow()
            main.postDelayed(this, Remote.HEARTBEAT_MS)
        }
    }
    private val soon = Runnable { reportNow() }

    fun start() {
        if (running) return
        running = true
        main.post(heartbeat)
        listener = thread(name = "soundscape-devices", isDaemon = true) { listen() }
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        val active = connection
        connection = null
        listener?.interrupt()
        if (active != null) disconnectOffMain(active)
        posts.shutdown()
    }

    fun reportSoon() {
        if (!running) return
        main.removeCallbacks(soon)
        main.postDelayed(soon, REPORT_AFTER_MS)
    }

    fun send(body: String, done: (Boolean) -> Unit) {
        later {
            val answer = post("/api/devices/command", body)
            val ok = answer != null && answer.status in 200..299
            main.post {
                if (running) done(ok)
            }
        }
    }

    private fun reportNow() {
        if (!running) return
        val body = Remote.report(id, name, snapshot())
        later { post("/api/devices", body) }
    }

    private fun take() {
        later {
            val payload = JSONObject().put("id", id).toString()
            val answer = post("/api/devices/take", payload) ?: return@later
            val commands = Remote.commands(answer.body)
            if (commands.isEmpty()) return@later
            main.post {
                if (!running) return@post
                commands.forEach(obey)
                reportSoon()
            }
        }
    }

    private fun later(work: () -> Unit) {
        if (!running) return
        try {
            posts.execute(work)
        } catch (_: RejectedExecutionException) {
        }
    }

    private fun stopOnUnauthorized() {
        if (!running) return
        running = false
        main.removeCallbacksAndMessages(null)
        val active = connection
        connection = null
        listener?.interrupt()
        if (active != null) disconnectOffMain(active)
        posts.shutdown()
    }

    private fun disconnectOffMain(active: HttpURLConnection) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Pools.io.execute {
                runCatching { active.disconnect() }
            }
        } else {
            runCatching { active.disconnect() }
        }
    }

    private fun post(path: String, body: String): HttpAnswer? = runCatching {
        val answer = Net.postJson(
            address = "$server$path",
            body = body,
            token = token,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
        )
        if (answer.status == 401) {
            stopOnUnauthorized()
            return@runCatching null
        }
        answer
    }.getOrNull()

    private fun listen() {
        while (running) {
            runCatching {
                val live = Net.open(
                    address = "$server/api/devices/live",
                    method = "GET",
                    token = token,
                    connectTimeoutMs = 15_000,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    accept = "text/event-stream",
                )
                connection = live
                try {
                    val status = live.responseCode
                    if (status == 401) {
                        stopOnUnauthorized()
                        return
                    }
                    if (status == 200) {
                        take()
                        val reader = EventReader()
                        live.inputStream.bufferedReader().use { input ->
                            while (running) {
                                val line = input.readLine() ?: break
                                val event = reader.line(line) ?: continue
                                if (event.first == "command" && Remote.isForMe(event.second, id)) take()
                            }
                        }
                    }
                } finally {
                    if (connection === live) connection = null
                    live.disconnect()
                }
            }
            if (!running) break
            try {
                Thread.sleep(RETRY_AFTER_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private companion object {
        const val REPORT_AFTER_MS = 400L
        const val READ_TIMEOUT_MS = 60_000
        const val RETRY_AFTER_MS = 5_000L
    }
}
