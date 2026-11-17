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
import androidx.core.net.toUri
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
        val context = applicationContext
        val settings = Settings(context)
        checkForUpdate(context, settings)
        val server = settings.server ?: return Result.success()
        val token = settings.token ?: return Result.success()
        OfflinePlays.send(context)
        val (status, body) = fetch(Notices.address(server, settings.lastNotification), token) ?: return Result.success()
        if (status == 401) {
            settings.forgetConnection()
            stop(context)
            return Result.success()
        }
        if (status == 200 && body != null && settings.notifications) {
            Notices.parse(body)?.let { items ->
                val (show, last) = Notices.fresh(items, settings.lastNotification)
                settings.lastNotification = last
                Notices.summary(show).forEach { post(context, it) }
            }
        }
        if (settings.notifications && settings.weeklyRecap) checkRecap(context, settings, server, token)
        if (settings.notifications && settings.streakReminder) checkStreak(context, settings, server, token)
        return Result.success()
    }

    private fun checkRecap(context: Context, settings: Settings, server: String, token: String) {
        val (status, body) = fetch("$server/api/recap/week", token) ?: return
        if (status != 200 || body == null) return
        val (show, recap) = Extras.recap(body) ?: return
        if (!Extras.recapDue(show, recap, settings.lastRecap) || recap == null) return
        settings.lastRecap = recap.from
        post(context, Shown(context.getString(R.string.recap_title), Extras.recapBody(recap), "/"))
    }

    private fun checkStreak(context: Context, settings: Settings, server: String, token: String) {
        val (status, body) = fetch("$server/api/today", token) ?: return
        if (status != 200 || body == null) return
        val today = Extras.today(body) ?: return
        val now = java.util.Calendar.getInstance()
        val date = String.format(
            java.util.Locale.US,
            "%04d-%02d-%02d",
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        if (!Extras.remind(today, hour, date, true, settings.lastReminder)) return
        settings.lastReminder = date
        post(context, Shown(Extras.reminderTitle(today), Extras.REMINDER_BODY, "/"))
    }

    companion object {
        private const val CHANNEL = "notifications"
        private const val EVERY = "notifications"
        private const val NOW = "notifications-now"

        fun checkForUpdate(context: Context, settings: Settings) {
            if (!settings.updateNotice) return
            val now = System.currentTimeMillis()
            if (now - settings.updateCheckedAt < Extras.UPDATE_EVERY_MS) return
            val (status, body) = fetch(Extras.LATEST, null) ?: return
            settings.updateCheckedAt = now
            if (status != 200 || body == null) return
            val latest = Extras.releaseVersion(body) ?: return
            if (!Extras.newer(BuildConfig.VERSION_NAME, latest)) {
                settings.updateAvailable = null
                return
            }
            settings.updateAvailable = latest
            if (!Extras.shouldTell(latest, settings.updateNotice, settings.toldUpdate)) return
            settings.toldUpdate = latest
            post(
                context,
                Shown(
                    context.getString(R.string.update_title, latest),
                    context.getString(R.string.update_body, BuildConfig.VERSION_NAME),
                    Extras.RELEASES,
                    outside = true,
                ),
            )
        }

        fun fetch(address: String, token: String?): Pair<Int, String?>? {
            val connection = runCatching { URL(address).openConnection() as HttpURLConnection }.getOrNull() ?: return null
            return try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                if (token != null) connection.setRequestProperty("Authorization", "Token $token")
                connection.setRequestProperty("User-Agent", "SoundscapeAndroid/${BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                val body = if (status == 200) connection.inputStream.bufferedReader().use { it.readText() } else null
                status to body
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

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
            val open = if (shown.outside && shown.url != null) {
                Intent(Intent.ACTION_VIEW, shown.url.toUri())
            } else {
                Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN)
                    .putExtra(MainActivity.EXTRA_PAGE, shown.url ?: "/notifications")
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
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
