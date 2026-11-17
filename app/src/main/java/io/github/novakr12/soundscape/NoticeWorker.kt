package io.github.novakr12.soundscape

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class NoticeWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val settings = Settings(applicationContext)
        val server = settings.server ?: return Result.success()
        val token = settings.token ?: return Result.success()
        OfflinePlays.send(applicationContext)
        if (!settings.notifications) return Result.success()
        val connection = runCatching {
            (URL(Notices.address(server, settings.lastNotification)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Token $token")
                setRequestProperty("User-Agent", "SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
            }
        }.getOrNull() ?: return Result.success()
        try {
            val status = runCatching { connection.responseCode }.getOrNull() ?: return Result.success()
            if (status == 401) {
                settings.forgetConnection()
                stop(applicationContext)
                return Result.success()
            }
            if (status != 200) return Result.success()
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val items = Notices.parse(body) ?: return Result.success()
            val (show, last) = Notices.fresh(items, settings.lastNotification)
            settings.lastNotification = last
            Notices.summary(show).forEach { post(applicationContext, it) }
        } catch (_: Exception) {
        } finally {
            connection.disconnect()
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "notifications"
        private const val EVERY = "notifications"
        private const val NOW = "notifications-now"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun start(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                EVERY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
            )
        }

        fun checkNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<NoticeWorker>().setConstraints(online).build(),
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(EVERY)
        }

        fun allowed(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

        private fun post(context: Context, shown: Shown) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.notifications_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val id = (shown.title + shown.body + System.nanoTime()).hashCode()
            val open = Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN)
                .putExtra(MainActivity.EXTRA_PAGE, shown.url ?: "/notifications")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val tap = PendingIntent.getActivity(
                context,
                id,
                open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(shown.title)
                .setContentText(shown.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
        }
    }
}
