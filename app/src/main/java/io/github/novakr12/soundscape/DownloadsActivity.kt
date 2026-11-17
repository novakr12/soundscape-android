package io.github.novakr12.soundscape

import android.content.ComponentName
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

@OptIn(UnstableApi::class)
class DownloadsActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private var controller: ListenableFuture<MediaController>? = null
    private val refresh: () -> Unit = { runOnUiThread { show() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        scroll.addView(list)
        setContentView(scroll)
        title = getString(R.string.downloads_title)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(top = bars.top, bottom = bars.bottom, left = bars.left, right = bars.right)
            insets
        }
        Downloads.watch(refresh)
        show()
    }

    override fun onDestroy() {
        Downloads.unwatch(refresh)
        controller?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }

    private fun show() {
        list.removeAllViews()
        list.addView(text(getString(R.string.downloads_title), 24f, bold = true))
        val stored = Downloads.stored(this)
        list.addView(text(Downloads.summary(this) ?: getString(R.string.downloads_none), 15f, muted = true))
        list.addView(
            SwitchCompat(this).apply {
                text = getString(R.string.downloads_wifi_only)
                isChecked = Settings(this@DownloadsActivity).downloadsWifiOnly
                setOnCheckedChangeListener { _, checked -> Downloads.setWifiOnly(this@DownloadsActivity, checked) }
                minHeight = dp(48)
            },
        )
        if (stored.isEmpty()) {
            list.addView(text(getString(R.string.downloads_how), 15f, muted = true))
            return
        }
        val groups = stored.groupBy { DownloadData.groupName(it.track) to it.track.artist }
            .toSortedMap(compareBy({ it.first.lowercase() }, { it.second.lowercase() }))
        for ((key, items) in groups) {
            val (album, artist) = key
            list.addView(space(20))
            list.addView(text(album, 18f, bold = true))
            val done = items.count { it.state == Download.STATE_COMPLETED }
            val line = if (done == items.size) {
                DownloadData.summary(done, items.sumOf { it.bytes }) ?: ""
            } else {
                getString(R.string.downloads_progress, done, items.size)
            }
            list.addView(text("$artist · $line", 14f, muted = true))
            val playable = items.filter { it.state == Download.STATE_COMPLETED }.map { it.track }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (playable.isNotEmpty()) {
                row.addView(button(getString(R.string.downloads_play)) { play(playable, 0) })
            }
            row.addView(
                button(getString(R.string.downloads_remove)) {
                    Downloads.remove(this, items.map { it.track.trackId })
                },
            )
            list.addView(row)
            for (item in items) {
                val state = when (item.state) {
                    Download.STATE_COMPLETED -> ""
                    Download.STATE_FAILED -> " · " + getString(R.string.downloads_failed)
                    else -> " · " + getString(R.string.downloads_waiting, item.percent.coerceAtLeast(0f).toInt())
                }
                val song = text("${item.track.title}$state", 15f)
                song.minHeight = dp(40)
                song.gravity = Gravity.CENTER_VERTICAL
                if (item.state == Download.STATE_COMPLETED) {
                    song.setOnClickListener { play(playable, playable.indexOf(item.track)) }
                    song.isClickable = true
                }
                list.addView(song)
            }
        }
        list.addView(space(28))
        list.addView(
            button(getString(R.string.downloads_remove_all)) {
                AlertDialog.Builder(this)
                    .setMessage(R.string.downloads_remove_all_ask)
                    .setPositiveButton(R.string.downloads_remove_all) { _, _ -> Downloads.removeAll(this) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            },
        )
    }

    private fun play(tracks: List<BridgeTrack>, at: Int) {
        if (tracks.isEmpty()) return
        val start = { Playback.service?.playDownloads(tracks, at.coerceAtLeast(0)) }
        if (Playback.service != null && controller != null) {
            start()
            return
        }
        val future = controller ?: MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .buildAsync()
            .also { controller = it }
        future.addListener({ start() }, MoreExecutors.directExecutor())
    }

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(ContextCompat.getColor(context, if (muted) R.color.muted else R.color.text))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(height))
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
}
