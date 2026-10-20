package dev.wahha.wtube.player

import android.os.SystemClock
import dev.wahha.wtube.data.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

// Process-wide state shared by PlaybackService (which acts on it, also in the background) and the player UI
// (which shows it): both run in the app's one process, like the YouTube cache and Settings.current.

/** The next video, played by [PlaybackService] at [atMs] (elapsed realtime) while the end screen counts down. */
class Upcoming(val video: VideoItem, val atMs: Long)

object Autoplay {
    private val state = MutableStateFlow<Upcoming?>(null)
    val upcoming: StateFlow<Upcoming?> = state.asStateFlow()

    fun schedule(video: VideoItem, delayMs: Long) {
        state.value = Upcoming(video, SystemClock.elapsedRealtime() + delayMs)
    }

    fun playNow() {
        state.value = state.value?.let { Upcoming(it.video, SystemClock.elapsedRealtime()) }
    }

    fun cancel() {
        state.value = null
    }
}

/**
 * Videos picked to play after the current one, in order. When a video ends, [PlaybackService] takes the
 * first of them before autoplay looks at related videos. Lives as long as the process, like YouTube's queue.
 */
object PlayQueue {
    private val state = MutableStateFlow<List<VideoItem>>(emptyList())
    val items: StateFlow<List<VideoItem>> = state.asStateFlow()

    fun playNext(video: VideoItem) = state.update { listOf(video) + it.without(video.id) }

    fun add(video: VideoItem) = state.update { it.without(video.id) + video }

    fun replace(videos: List<VideoItem>) {
        state.value = videos.distinctBy { it.id }
    }

    fun remove(id: String) = state.update { it.without(id) }

    fun move(from: Int, to: Int) = state.update { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun clear() {
        state.value = emptyList()
    }

    /** Takes the first video off the queue. */
    fun pop(): VideoItem? = state.getAndUpdate { it.drop(1) }.firstOrNull()

    private fun List<VideoItem>.without(id: String) = filterNot { it.id == id }
}

/** Pauses playback at [atMs] (elapsed realtime), or when the current video ends if [endOfVideo]. */
class Sleep(val atMs: Long?, val endOfVideo: Boolean)

object SleepTimer {
    private val state = MutableStateFlow<Sleep?>(null)
    val sleep: StateFlow<Sleep?> = state.asStateFlow()

    fun inMinutes(minutes: Int) {
        state.value = Sleep(SystemClock.elapsedRealtime() + minutes * 60_000L, endOfVideo = false)
    }

    fun atEndOfVideo() {
        state.value = Sleep(null, endOfVideo = true)
    }

    fun cancel() {
        state.value = null
    }
}
