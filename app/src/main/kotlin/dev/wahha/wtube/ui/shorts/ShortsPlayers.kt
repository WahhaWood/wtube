@file:OptIn(UnstableApi::class)

package dev.wahha.wtube.ui.shorts

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.getSystemService
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.capLongSide
import dev.wahha.wtube.data.qualityCap
import dev.wahha.wtube.data.watchUrl
import dev.wahha.wtube.player.WatchReporter
import dev.wahha.wtube.player.YoutubeMediaSourceFactory
import kotlinx.coroutines.CoroutineScope

/**
 * The Shorts feed's own players, apart from the playback service: no session, notification, queue or
 * background play. Three of them, page `i` on player `i % 3`, so the short on screen and both its
 * neighbours are prepared at once and a swipe lands on a video already buffered.
 */
class ShortsPlayers(context: Context, scope: CoroutineScope) {
    private val connectivity = context.getSystemService<ConnectivityManager>()
    private val players = List(SLOTS) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(YoutubeMediaSourceFactory())
            // Taking audio focus pauses the regular video in the mini player, as one sound at a time should.
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(WatchReporter(scope, this, WatchReporter.SHORTS))
            }
    }

    /** The video id each player holds, by slot; state, so a page shows the video once its player has it. */
    private val bound = mutableStateListOf<String?>(*arrayOfNulls(SLOTS))

    fun at(index: Int): ExoPlayer = players[index.mod(SLOTS)]

    fun holds(index: Int, id: String): Boolean = bound[index.mod(SLOTS)] == id

    /** Loads short [id] into page [index]'s player, paused; its stream info must be in [YouTube]'s cache. */
    fun bind(index: Int, id: String) {
        val slot = index.mod(SLOTS)
        if (bound[slot] == id) return
        bound[slot] = id
        val player = players[slot]
        val cap = qualityCap(Settings.current.value, connectivity?.isActiveNetworkMetered == true)?.let(::capLongSide) ?: Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(cap, cap)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.playWhenReady = false
        player.setMediaItem(MediaItem.Builder().setMediaId(id).setUri(watchUrl(id)).build())
        player.prepare()
    }

    /** After a playback error: fresh stream URLs, the same short again. */
    fun retry(index: Int, id: String) {
        YouTube.forget(id)
        bound[index.mod(SLOTS)] = null
    }

    fun setMuted(muted: Boolean) = players.forEach { it.volume = if (muted) 0f else 1f }

    fun release() = players.forEach(ExoPlayer::release)

    private companion object {
        const val SLOTS = 3
    }
}
