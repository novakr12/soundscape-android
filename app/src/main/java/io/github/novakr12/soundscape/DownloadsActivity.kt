package io.github.novakr12.soundscape

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
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

@OptIn(UnstableApi::class)
class DownloadsActivity : AppCompatActivity() {
    private data class ItemKey(val trackId: String, val state: Int, val bytes: Long, val percent: Int)
    private data class ViewKey(val summary: String?, val wifiOnly: Boolean, val items: List<ItemKey>)

    private sealed class Row {
        data class Header(val summary: String?, val wifiOnly: Boolean, val empty: Boolean) : Row()
        data class GroupHeader(
            val album: String,
            val meta: String,
            val playable: List<BridgeTrack>,
            val trackIds: List<String>,
        ) : Row()

        data class Song(val label: String, val playable: List<BridgeTrack>?, val index: Int) : Row()
        data object Footer : Row()
    }

    private lateinit var listView: ListView
    private lateinit var adapter: DownloadsAdapter
    private val main = Handler(Looper.getMainLooper())
    private var controller: ListenableFuture<MediaController>? = null
    private var connectedController: MediaController? = null
    private var pendingPlay: Pair<List<BridgeTrack>, Int>? = null
    private var lastShown: ViewKey? = null
    private val render = Runnable { show() }
    private val refresh: () -> Unit = {
        main.removeCallbacks(render)
        main.postDelayed(render, REFRESH_DEBOUNCE_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = DownloadsAdapter()
        val padSide = dp(20)
        val padTop = dp(20)
        val padBottom = dp(32)
        listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            clipToPadding = false
            setPadding(padSide, padTop, padSide, padBottom)
            adapter = this@DownloadsActivity.adapter
        }
        setContentView(listView)
        title = getString(R.string.downloads_title)
        ViewCompat.setOnApplyWindowInsetsListener(listView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                top = padTop + bars.top,
                bottom = padBottom + bars.bottom,
                left = padSide + bars.left,
                right = padSide + bars.right,
            )
            insets
        }
        matchBarsToTheme()
        Downloads.watch(this, refresh)
        show()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        matchBarsToTheme()
    }

    override fun onDestroy() {
        Downloads.unwatch(this, refresh)
        main.removeCallbacksAndMessages(null)
        pendingPlay = null
        connectedController = null
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }

    private fun show() {
        if (isFinishing || isDestroyed) return
        val stored = Downloads.stored(this)
        val summary = Downloads.summary(this)
        val wifiOnly = Settings(this).downloadsWifiOnly
        val key = ViewKey(
            summary = summary,
            wifiOnly = wifiOnly,
            items = stored.map { item ->
                ItemKey(
                    trackId = item.track.trackId,
                    state = item.state,
                    bytes = if (item.state == Download.STATE_COMPLETED) item.bytes else 0L,
                    percent = if (item.state == Download.STATE_COMPLETED || item.state == Download.STATE_FAILED) {
                        0
                    } else {
                        item.percent.coerceAtLeast(0f).toInt()
                    },
                )
            },
        )
        if (key == lastShown) return
        lastShown = key

        val rows = ArrayList<Row>(stored.size + 4)
        rows.add(Row.Header(summary = summary, wifiOnly = wifiOnly, empty = stored.isEmpty()))
        if (stored.isNotEmpty()) {
            val groups = stored.groupBy { DownloadData.groupName(it.track) to it.track.artist }
                .toSortedMap(compareBy({ it.first.lowercase() }, { it.second.lowercase() }))
            for ((groupKey, items) in groups) {
                val (album, artist) = groupKey
                val done = items.count { it.state == Download.STATE_COMPLETED }
                val line = if (done == items.size) {
                    DownloadData.summary(done, items.sumOf { it.bytes }) ?: ""
                } else {
                    getString(R.string.downloads_progress, done, items.size)
                }
                val playable = items.filter { it.state == Download.STATE_COMPLETED }.map { it.track }
                rows.add(
                    Row.GroupHeader(
                        album = album,
                        meta = "$artist · $line",
                        playable = playable,
                        trackIds = items.map { it.track.trackId },
                    ),
                )
                for (item in items) {
                    val state = when (item.state) {
                        Download.STATE_COMPLETED -> ""
                        Download.STATE_FAILED -> " · " + getString(R.string.downloads_failed)
                        else -> " · " + getString(R.string.downloads_waiting, item.percent.coerceAtLeast(0f).toInt())
                    }
                    val index = if (item.state == Download.STATE_COMPLETED) playable.indexOf(item.track) else -1
                    rows.add(
                        Row.Song(
                            label = "${item.track.title}$state",
                            playable = playable.takeIf { index >= 0 },
                            index = index,
                        ),
                    )
                }
            }
            rows.add(Row.Footer)
        }
        adapter.submit(rows)
    }

    private fun play(tracks: List<BridgeTrack>, at: Int) {
        if (tracks.isEmpty() || isFinishing || isDestroyed) return
        val index = at.coerceAtLeast(0)
        connectedController?.takeIf { it.isConnected }?.let { active ->
            sendPlayDownloads(active, tracks, index)
            return
        }
        pendingPlay = tracks to index
        if (controller != null) return
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java)))
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(c: MediaController) {
                    if (connectedController === c) connectedController = null
                    controller = null
                }
            })
            .buildAsync()
            .also { controller = it }
        future.addListener({
            if (isFinishing || isDestroyed) return@addListener
            val built = runCatching { future.get() }.getOrNull()
            if (built == null || !built.isConnected) {
                if (controller === future) controller = null
                connectedController = null
                return@addListener
            }
            connectedController = built
            val pending = pendingPlay
            pendingPlay = null
            if (pending != null) {
                sendPlayDownloads(built, pending.first, pending.second)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun sendPlayDownloads(target: MediaController, tracks: List<BridgeTrack>, at: Int) {
        target.sendCustomCommand(
            PlaybackBridge.CMD_PLAY_DOWNLOADS,
            Bundle().apply {
                putString(PlaybackBridge.KEY_TRACKS_JSON, PlaybackCodecs.encodeTracks(tracks))
                putInt(PlaybackBridge.KEY_INDEX, at)
            },
        )
    }

    private inner class DownloadsAdapter : BaseAdapter() {
        private var rows: List<Row> = emptyList()

        fun submit(next: List<Row>) {
            rows = next
            notifyDataSetChanged()
        }

        override fun getCount(): Int = rows.size

        override fun getItem(position: Int): Row = rows[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 4

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.GroupHeader -> TYPE_GROUP
            is Row.Song -> TYPE_SONG
            Row.Footer -> TYPE_FOOTER
        }

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val item = rows[position]) {
                is Row.Header -> bindHeader(item, convertView)
                is Row.GroupHeader -> bindGroup(item, convertView)
                is Row.Song -> bindSong(item, convertView)
                Row.Footer -> bindFooter(convertView)
            }
        }

        private fun bindHeader(item: Row.Header, convertView: View?): View {
            val view: LinearLayout
            val holder: HeaderHolder
            if (convertView == null) {
                val titleView = text(getString(R.string.downloads_title), 24f, bold = true)
                val summaryView = text("", 15f, muted = true)
                val wifiSwitch = SwitchCompat(this@DownloadsActivity).apply {
                    text = getString(R.string.downloads_wifi_only)
                    minHeight = dp(48)
                }
                val howView = text(getString(R.string.downloads_how), 15f, muted = true)
                view = LinearLayout(this@DownloadsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleView)
                    addView(summaryView)
                    addView(wifiSwitch)
                    addView(howView)
                }
                holder = HeaderHolder(summaryView, wifiSwitch, howView)
                view.tag = holder
            } else {
                view = convertView as LinearLayout
                holder = view.tag as HeaderHolder
            }
            holder.summary.text = item.summary ?: getString(R.string.downloads_none)
            holder.wifi.setOnCheckedChangeListener(null)
            holder.wifi.isChecked = item.wifiOnly
            holder.wifi.setOnCheckedChangeListener { _, checked ->
                Downloads.setWifiOnly(this@DownloadsActivity, checked)
            }
            holder.how.visibility = if (item.empty) View.VISIBLE else View.GONE
            return view
        }

        private fun bindGroup(item: Row.GroupHeader, convertView: View?): View {
            val view: LinearLayout
            val holder: GroupHolder
            if (convertView == null) {
                val albumView = text("", 18f, bold = true)
                val metaView = text("", 14f, muted = true)
                val playButton = button(getString(R.string.downloads_play))
                val removeButton = button(getString(R.string.downloads_remove))
                val actions = LinearLayout(this@DownloadsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(playButton)
                    addView(removeButton)
                }
                view = LinearLayout(this@DownloadsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(20), 0, 0)
                    addView(albumView)
                    addView(metaView)
                    addView(actions)
                }
                holder = GroupHolder(albumView, metaView, playButton, removeButton)
                view.tag = holder
            } else {
                view = convertView as LinearLayout
                holder = view.tag as GroupHolder
            }
            holder.album.text = item.album
            holder.meta.text = item.meta
            if (item.playable.isNotEmpty()) {
                holder.play.visibility = View.VISIBLE
                holder.play.setOnClickListener { play(item.playable, 0) }
            } else {
                holder.play.visibility = View.GONE
                holder.play.setOnClickListener(null)
            }
            holder.remove.setOnClickListener {
                Downloads.remove(this@DownloadsActivity, item.trackIds)
            }
            return view
        }

        private fun bindSong(item: Row.Song, convertView: View?): View {
            val song = (convertView as? TextView) ?: text("", 15f).apply {
                minHeight = dp(40)
                gravity = Gravity.CENTER_VERTICAL
            }
            song.text = item.label
            if (item.playable != null && item.index >= 0) {
                song.setOnClickListener { play(item.playable, item.index) }
                song.isClickable = true
            } else {
                song.setOnClickListener(null)
                song.isClickable = false
            }
            return song
        }

        private fun bindFooter(convertView: View?): View {
            val view = (convertView as? LinearLayout) ?: LinearLayout(this@DownloadsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(28), 0, 0)
                addView(
                    button(getString(R.string.downloads_remove_all)).apply {
                        setOnClickListener {
                            AlertDialog.Builder(this@DownloadsActivity)
                                .setMessage(R.string.downloads_remove_all_ask)
                                .setPositiveButton(R.string.downloads_remove_all) { _, _ ->
                                    Downloads.removeAll(this@DownloadsActivity)
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    },
                )
            }
            return view
        }
    }

    private class HeaderHolder(val summary: TextView, val wifi: SwitchCompat, val how: TextView)

    private class GroupHolder(
        val album: TextView,
        val meta: TextView,
        val play: Button,
        val remove: Button,
    )

    private fun text(value: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(ContextCompat.getColor(context, if (muted) R.color.muted else R.color.text))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun button(label: String) = Button(this).apply {
        text = label
    }

    private fun dp(value: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val REFRESH_DEBOUNCE_MS = 250L
        const val TYPE_HEADER = 0
        const val TYPE_GROUP = 1
        const val TYPE_SONG = 2
        const val TYPE_FOOTER = 3
    }
}
