package io.github.novakr12.soundscape

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpAnswer(val status: Int, val body: String?)

object Net {
    val userAgent: String get() = "SoundscapeAndroid/${BuildConfig.VERSION_NAME}"
    const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024
    private const val INITIAL_BUFFER_BYTES = 4096
    private const val MAX_DRAIN_BYTES = 4096

    fun open(
        address: String,
        method: String = "GET",
        token: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        accept: String? = null,
        contentType: String? = null,
    ): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("User-Agent", userAgent)
        if (token != null) connection.setRequestProperty("Authorization", "Token $token")
        if (accept != null) connection.setRequestProperty("Accept", accept)
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
        return connection
    }

    fun get(
        address: String,
        token: String? = null,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 15_000,
        accept: String? = "application/json",
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): HttpAnswer {
        val connection = open(
            address = address,
            method = "GET",
            token = token,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            accept = accept,
        )
        return try {
            val status = connection.responseCode
            val body = if (status in 200..299) {
                readTextBounded(connection.inputStream, maxBytes)
            } else {
                drainQuietly(connection.errorStream)
                null
            }
            HttpAnswer(status, body)
        } finally {
            connection.disconnect()
        }
    }

    fun postJson(
        address: String,
        body: String,
        token: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 15_000,
        readResponse: Boolean = true,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): HttpAnswer {
        val connection = open(
            address = address,
            method = "POST",
            token = token,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            contentType = "application/json",
        )
        return try {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = if (status in 200..299) {
                if (readResponse) {
                    readTextBounded(connection.inputStream, maxBytes)
                } else {
                    drainQuietly(connection.inputStream)
                    null
                }
            } else {
                drainQuietly(connection.errorStream)
                null
            }
            HttpAnswer(status, text)
        } finally {
            connection.disconnect()
        }
    }

    fun delete(
        address: String,
        token: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): Int {
        val connection = open(
            address = address,
            method = "DELETE",
            token = token,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
        )
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            drainQuietly(stream)
            status
        } finally {
            connection.disconnect()
        }
    }

    fun readTextBounded(input: InputStream, maxBytes: Int): String {
        if (maxBytes <= 0) {
            input.use {}
            return ""
        }
        return input.use { stream ->
            var buf = ByteArray(minOf(INITIAL_BUFFER_BYTES, maxBytes))
            var total = 0
            while (total < maxBytes) {
                if (total == buf.size) {
                    val nextSize = minOf(maxBytes, maxOf(buf.size * 2, total + 1))
                    buf = buf.copyOf(nextSize)
                }
                val read = stream.read(buf, total, minOf(buf.size - total, maxBytes - total))
                if (read <= 0) break
                total += read
            }
            String(buf, 0, total, Charsets.UTF_8)
        }
    }

    private fun drainQuietly(stream: InputStream?, maxBytes: Int = MAX_DRAIN_BYTES) {
        if (stream == null) return
        runCatching {
            stream.use { input ->
                val scratch = ByteArray(1024)
                var remaining = maxBytes
                while (remaining > 0) {
                    val read = input.read(scratch, 0, minOf(scratch.size, remaining))
                    if (read <= 0) break
                    remaining -= read
                }
            }
        }
    }

    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > maxBytes) return -1L
            output.write(buffer, 0, read)
        }
        return total
    }
}
