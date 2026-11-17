package io.github.novakr12.soundscape

import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class PagePlayer(looper: Looper, private val send: (String) -> Unit) : SimpleBasePlayer(looper) {
    private var page: PageState? = null
    private var server = ""

    fun show(state: PageState?, server: String) {
        page = state
        this.server = server
        invalidateState()
    }

    val shown: PageState? get() = page

    override fun getState(): State {
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
            )
            .build()
        val state = page
        if (state == null) {
            return State.Builder().setAvailableCommands(commands).setPlaybackState(Player.STATE_IDLE).build()
        }
        val items = state.tracks.mapIndexed { index, track ->
            val length = if (index == state.position) state.duration ?: track.duration else track.duration
            MediaItemData.Builder("$index:${track.trackId}")
                .setMediaItem(mediaItemFor(server, track))
                .setDurationUs(length?.let { (it * 1_000_000).toLong() } ?: C.TIME_UNSET)
                .setIsSeekable(length != null)
                .build()
        }
        val positionMs = (state.time * 1000).toLong()
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(items)
            .setCurrentMediaItemIndex(state.position)
            .setContentPositionMs(
                if (state.playing) {
                    PositionSupplier.getExtrapolating(positionMs, 1f)
                } else {
                    PositionSupplier.getConstant(positionMs)
                },
            )
            .setPlayWhenReady(state.playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_READY)
            .setRepeatMode(Bridge.repeatMode(state.repeat))
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        send(Bridge.command(if (playWhenReady) "play" else "pause"))
        page = page?.copy(playing = playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val state = page ?: return Futures.immediateVoidFuture()
        if (mediaItemIndex in state.tracks.indices) {
            page = state.copy(position = mediaItemIndex, time = positionMs.coerceAtLeast(0) / 1000.0, duration = null)
        }
        val command = when {
            seekCommand == Player.COMMAND_SEEK_TO_NEXT || seekCommand == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ->
                Bridge.command("next")
            mediaItemIndex != state.position &&
                (seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS || seekCommand == Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ->
                Bridge.command("previous")
            mediaItemIndex == state.position -> Bridge.command("seek", positionMs.coerceAtLeast(0) / 1000.0)
            else -> null
        }
        command?.let(send)
        return Futures.immediateVoidFuture()
    }

    companion object {
        fun mediaItemFor(server: String, track: BridgeTrack, stream: String? = null, cacheKey: String? = null): MediaItem {
            val metadata = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setAlbumTitle(track.album)
                .setArtworkUri(Bridge.coverAddress(server, track.cover)?.let(Uri::parse))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
            val builder = MediaItem.Builder().setMediaId(track.trackId).setMediaMetadata(metadata)
            if (stream != null) builder.setUri(stream)
            if (cacheKey != null) builder.setCustomCacheKey(cacheKey)
            return builder.build()
        }
    }
}
