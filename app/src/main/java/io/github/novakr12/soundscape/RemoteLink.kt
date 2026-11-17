package io.github.novakr12.soundscape

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
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
    private val posts = Executors.newSingleThreadExecutor()

    @Volatile
    private var running = false

    @Volatile
    private var connection: HttpURLConnection? = null
    private var listener: Thread? = null

    private val heartbeat = object : Runnable {
        override fun run() {
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
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(soon)
        connection?.disconnect()
        listener?.interrupt()
        posts.shutdown()
    }

    fun reportSoon() {
        if (!running) return
        main.removeCallbacks(soon)
        main.postDelayed(soon, REPORT_AFTER_MS)
    }

    fun send(body: String, done: (Boolean) -> Unit) {
        later {
            val ok = post("/api/devices/command", body)?.first in 200..299
            main.post { done(ok) }
        }
    }

    private fun reportNow() {
        if (!running) return
        val body = Remote.report(id, name, snapshot())
        later { post("/api/devices", body) }
    }

    private fun take() {
        later {
            val answer = post("/api/devices/take", "{\"id\":\"$id\"}") ?: return@later
            val commands = Remote.commands(answer.second)
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

    private fun post(path: String, body: String): Pair<Int, String?>? = runCatching {
        val sending = URL("$server$path").openConnection() as HttpURLConnection
        try {
            sending.requestMethod = "POST"
            sending.connectTimeout = 10_000
            sending.readTimeout = 10_000
            sending.doOutput = true
            sending.setRequestProperty("Authorization", "Token $token")
            sending.setRequestProperty("Content-Type", "application/json")
            sending.setRequestProperty("User-Agent", "SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
            sending.outputStream.use { it.write(body.toByteArray()) }
            val status = sending.responseCode
            val text = if (status in 200..299) sending.inputStream.bufferedReader().use { it.readText() } else null
            status to text
        } finally {
            sending.disconnect()
        }
    }.getOrNull()

    private fun listen() {
        while (running) {
            runCatching {
                val live = URL("$server/api/devices/live").openConnection() as HttpURLConnection
                connection = live
                try {
                    live.connectTimeout = 15_000
                    live.readTimeout = READ_TIMEOUT_MS
                    live.setRequestProperty("Authorization", "Token $token")
                    live.setRequestProperty("Accept", "text/event-stream")
                    live.setRequestProperty("User-Agent", "SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
                    if (live.responseCode == 200) {
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
