package dev.wahha.wtube.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Segment
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.SponsorBlock
import dev.wahha.wtube.data.VideoSegments
import dev.wahha.wtube.ui.components.Notices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Skips (or mutes) the SponsorBlock segments of the playing video. Lives in [PlaybackService], so it
 * works with the app in the background. A segment is skipped only when playback runs into it: one the
 * user seeks into on purpose plays.
 */
class SegmentSkipper(private val context: Context, private val scope: CoroutineScope, private val player: Player) : Player.Listener {
    private var load: Job? = null
    private var tick: Job? = null
    private var previous = -1L
    private var muted = false
    private var volumeBeforeMute = 1f

    init {
        scope.launch {
            Settings.current.map { it.sponsorBlock to it.sponsorCategories }.distinctUntilChanged().drop(1).collect { reload() }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = reload()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        tick?.cancel()
        if (isPlaying) {
            tick = scope.launch {
                while (true) {
                    check()
                    delay(TICK_MS)
                }
            }
        }
    }

    private fun reload() {
        load?.cancel()
        setMuted(false)
        previous = -1
        val id = player.currentMediaItem?.mediaId
        val settings = Settings.current.value
        SponsorBlock.show(null)
        if (id == null || !settings.sponsorBlock) return
        load = scope.launch {
            val segments = runCatching { SponsorBlock.segments(id, settings.sponsorCategories) }
                .onFailure { Log.w("SponsorBlock", "No segments for this video: $it") }
                .getOrDefault(emptyList())
            if (player.currentMediaItem?.mediaId == id) SponsorBlock.show(VideoSegments(id, segments))
        }
    }

    private fun check() {
        val current = SponsorBlock.current.value ?: return
        if (current.videoId != player.currentMediaItem?.mediaId) return
        val position = player.currentPosition
        setMuted(current.segments.firstOrNull { position in it }?.mute == true)
        val skip = segmentToSkip(current.segments, position, previous)
        if (skip != null) {
            player.seekTo(skip.endMs)
            Notices.show(R.string.sb_skipped, context.getString(sponsorCategoryLabel(skip.category)))
            // A segment right after this one starts where it ends, and must be skipped too.
            previous = -1
            return
        }
        previous = position
    }

    private fun setMuted(mute: Boolean) {
        if (mute == muted) return
        muted = mute
        if (mute) volumeBeforeMute = player.volume
        player.volume = if (mute) 0f else volumeBeforeMute
    }

    private companion object {
        const val TICK_MS = 250L
    }
}

// Playback reaches a segment within a tick or two of its start; deeper in means a seek.
private const val ENTRY_WINDOW_MS = 1_500L

/**
 * The segment to skip at [position], given the [previous] tick's position (-1 for none): one that playback
 * ran into just now. Muted segments play, and so does one the user sought into or was already in.
 */
fun segmentToSkip(segments: List<Segment>, position: Long, previous: Long): Segment? {
    val inside = segments.firstOrNull { position in it } ?: return null
    return inside.takeIf { !it.mute && previous !in it && position - it.startMs < ENTRY_WINDOW_MS }
}

fun sponsorCategoryLabel(category: String): Int = when (category) {
    "sponsor" -> R.string.sb_cat_sponsor
    "selfpromo" -> R.string.sb_cat_selfpromo
    "interaction" -> R.string.sb_cat_interaction
    "intro" -> R.string.sb_cat_intro
    "outro" -> R.string.sb_cat_outro
    "preview" -> R.string.sb_cat_preview
    "music_offtopic" -> R.string.sb_cat_music_offtopic
    "filler" -> R.string.sb_cat_filler
    else -> R.string.sb_cat_other
}
