package io.github.novakr12.soundscape

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException()
        val context = context ?: throw FileNotFoundException()
        val path = uri.getQueryParameter("path")?.takeIf { Car.isCoverPath(it) } ?: throw FileNotFoundException()
        val settings = Settings(context)
        val server = settings.server ?: throw FileNotFoundException()
        val folder = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(folder, name(server + path))
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            if (file.exists() && now - file.lastModified() <= KEEP_MS) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val token = settings.token
        val downloaded = if (token != null) download("$server$path", token, folder) else null
        synchronized(cacheLock) {
            if (downloaded != null) {
                if (!downloaded.renameTo(file)) {
                    downloaded.delete()
                }
            }
            if (!file.exists()) throw FileNotFoundException()
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            tidyLocked(folder, keep = file)
            return fd
        }
    }

    private fun download(address: String, token: String, folder: File): File? {
        val part = runCatching { File.createTempFile("cover", ".part", folder) }.getOrNull() ?: return null
        return try {
            val connection = Net.open(
                address = address,
                method = "GET",
                token = token,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 15_000,
            )
            val written = try {
                if (connection.responseCode != 200) {
                    -1L
                } else {
                    connection.inputStream.use { input ->
                        part.outputStream().use { output -> Net.copyBounded(input, output, MAX_COVER_BYTES) }
                    }
                }
            } finally {
                connection.disconnect()
            }
            if (written > 0L) {
                part
            } else {
                part.delete()
                null
            }
        } catch (_: Exception) {
            part.delete()
            null
        }
    }

    private fun tidyLocked(folder: File, keep: File) {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it != keep } ?: return
        val limit = (MOST_COVERS - 1).coerceAtLeast(0)
        if (files.size <= limit) return
        files.sortedBy { it.lastModified() }.take(files.size - limit).forEach { it.delete() }
    }

    private fun name(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        val cacheLock = Any()
        const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        const val MOST_COVERS = 300
        const val MAX_COVER_BYTES = 5L * 1024 * 1024
    }
}
