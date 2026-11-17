package io.github.novakr12.soundscape

import android.app.Notification
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

@OptIn(UnstableApi::class)
class SongDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL,
    R.string.downloads_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager = Downloads.manager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        DownloadNotificationHelper(this, CHANNEL).buildProgressNotification(
            this,
            R.drawable.ic_notification,
            null,
            getString(R.string.downloading_songs),
            downloads,
            notMetRequirements,
        )

    private companion object {
        const val NOTIFICATION_ID = 7301
        const val JOB_ID = 7302
        const val CHANNEL = "downloads"
    }
}
