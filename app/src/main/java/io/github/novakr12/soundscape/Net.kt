package io.github.novakr12.soundscape

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

data class HttpAnswer(val status: Int, val body: String?)

object Net {
    val userAgent: String get() = "SoundscapeAndroid/${BuildConfig.VERSION_NAME}"
    const val DEFAULT_MAX_BYTES = 2 * 1024 * 1024

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
            val body = if (status in 200..299) readTextBounded(connection.inputStream, maxBytes) else null
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
            val text = if (readResponse && status in 200..299) {
                readTextBounded(connection.inputStream, maxBytes)
            } else {
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
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    fun readTextBounded(input: InputStream, maxBytes: Int): String = input.use { stream ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        String(out.toByteArray(), Charsets.UTF_8)
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
