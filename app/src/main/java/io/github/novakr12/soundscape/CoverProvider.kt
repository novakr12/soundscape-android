package io.github.novakr12.soundscape

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class CoverProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri) = "image/jpeg"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException()
        val path = uri.getQueryParameter("path")?.takeIf { Car.isCoverPath(it) } ?: throw FileNotFoundException()
        val settings = Settings(context)
        val server = settings.server ?: throw FileNotFoundException()
        val folder = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File(folder, name(server + path))
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > KEEP_MS) {
            val token = settings.token ?: throw FileNotFoundException()
            if (!download("$server$path", token, folder, file) && !file.exists()) throw FileNotFoundException()
            tidy(folder)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun download(address: String, token: String, folder: File, file: File): Boolean {
        val part = File.createTempFile("cover", ".part", folder)
        return try {
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.setRequestProperty("Authorization", "Token $token")
                connection.setRequestProperty("User-Agent", "SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
                if (connection.responseCode != 200) return false
                connection.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            } finally {
                connection.disconnect()
            }
            part.length() > 0 && part.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            part.delete()
        }
    }

    private fun tidy(folder: File) {
        val files = folder.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        if (files.size <= MOST_COVERS) return
        files.sortedBy { it.lastModified() }.take(files.size - MOST_COVERS).forEach { it.delete() }
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
        const val KEEP_MS = 7 * 24 * 60 * 60 * 1000L
        const val MOST_COVERS = 300
    }
}
