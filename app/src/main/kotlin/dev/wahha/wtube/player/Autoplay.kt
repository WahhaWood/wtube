package dev.wahha.wtube.player

import android.content.Context
import android.os.SystemClock
import dev.wahha.wtube.data.JsonFile
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.ui.components.movedBefore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.io.File
import kotlin.math.abs

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
 * first of them before autoplay looks at related videos. Kept on disk for when the system kills the process;
 * closing the app by hand ([forget]) ends it.
 */
object PlayQueue {
    private val state = MutableStateFlow<List<VideoItem>>(emptyList())
    private val disk = JsonFile("PlayQueue", ListSerializer(VideoItem.serializer())) { state.value }
    private var file: File? = null
    val items: StateFlow<List<VideoItem>> = state.asStateFlow()

    /** Reads the queue back, or with [restore] false starts without it. */
    fun init(context: Context, restore: Boolean) {
        val from = File(context.filesDir, "queue.json")
        if (!restore) from.delete()
        load(from)
    }

    internal fun load(from: File) {
        file = from
        disk.load(from) { saved -> state.update { (it + saved.orEmpty()).distinctBy { video -> video.id } } }
    }

    fun playNext(video: VideoItem) = change { listOf(video) + it.without(video.id) }

    fun add(video: VideoItem) = change { it.without(video.id) + video }

    fun replace(videos: List<VideoItem>) = change { videos.distinctBy { it.id } }

    fun remove(id: String) = change { it.without(id) }

    /** Puts [id] right above [before], or last when null. */
    fun move(id: String, before: String?) = change { it.movedBefore(VideoItem::id, id, before) }

    fun clear() = change { emptyList() }

    /** Takes the first video off the queue. */
    fun pop(): VideoItem? = state.getAndUpdate { it.drop(1) }.firstOrNull().also { disk.save() }

    /** The app is being closed by hand: at once, not by the writer, as the process goes right after. */
    fun forget() {
        state.value = emptyList()
        file?.delete()
    }

    private fun change(block: (List<VideoItem>) -> List<VideoItem>) {
        state.update(block)
        disk.save()
    }

    private fun List<VideoItem>.without(id: String) = filterNot { it.id == id }
}

/** Pauses playback at [atMs] (elapsed realtime), or when the current video ends if [endOfVideo]. */
class Sleep(val atMs: Long?, val endOfVideo: Boolean)

/**
 * Whether the next video due at [upcomingAtMs] may start: not when the sleep timer is due by then. Both
 * wait on their own delays, and after a doze they wake together in either order; the timer must win.
 */
fun mayPlayUpcoming(upcomingAtMs: Long, sleep: Sleep?): Boolean = sleep?.atMs?.let { it > upcomingAtMs } ?: true

private const val BOOT_TOLERANCE_MS = 60_000L

/** [Sleep] on disk, with the clocks of when it was written: its [atMs] means nothing after a reboot. */
@Serializable
internal class SavedSleep(val atMs: Long?, val endOfVideo: Boolean, val elapsedMs: Long, val wallMs: Long) {
    /** The timer to go on with, or null if the phone has rebooted since or the time has passed. */
    fun restore(elapsedNow: Long, wallNow: Long): Sleep? {
        val sameBoot = abs((wallNow - elapsedNow) - (wallMs - elapsedMs)) < BOOT_TOLERANCE_MS
        if (!sameBoot || (atMs != null && atMs <= elapsedNow)) return null
        return Sleep(atMs, endOfVideo)
    }
}

object SleepTimer {
    private val state = MutableStateFlow<Sleep?>(null)
    private val disk = JsonFile("SleepTimer", SavedSleep.serializer().nullable) {
        state.value?.let { SavedSleep(it.atMs, it.endOfVideo, SystemClock.elapsedRealtime(), System.currentTimeMillis()) }
    }
    private var file: File? = null
    val sleep: StateFlow<Sleep?> = state.asStateFlow()

    /** Reads the timer back, or with [restore] false starts without it. */
    fun init(context: Context, restore: Boolean) {
        val from = File(context.filesDir, "sleep.json")
        if (!restore) from.delete()
        file = from
        disk.load(from) { saved ->
            val restored = saved?.restore(SystemClock.elapsedRealtime(), System.currentTimeMillis())
            state.compareAndSet(null, restored)
        }
    }

    fun inMinutes(minutes: Int) = set(Sleep(SystemClock.elapsedRealtime() + minutes * 60_000L, endOfVideo = false))

    fun atEndOfVideo() = set(Sleep(null, endOfVideo = true))

    fun cancel() = set(null)

    /** The app is being closed by hand: at once, not by the writer, as the process goes right after. */
    fun forget() {
        state.value = null
        file?.delete()
    }

    private fun set(sleep: Sleep?) {
        state.value = sleep
        disk.save()
    }
}
