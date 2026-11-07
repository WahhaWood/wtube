package dev.wahha.wtube.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import java.io.File

/** What Home and related videos leave out, on top of [Hidden] and [MembersOnly]. Search, channels and subscriptions show everything. */
object Recommendations {
    /** The choices of [AppSettings.minDurationMinutes]; 0 is no limit. */
    val MinDurations = listOf(0, 1, 3, 5, 10)

    /** From this share of its length a video counts as watched: the end credits need not be sat through. */
    const val WATCHED_SHARE = 0.9f

    fun watchedIds(fractions: Map<String, Float>): Set<String> = fractions.filterValues { it >= WATCHED_SHARE }.keys.toSet()

    /** A video of unknown length (a live one, an upcoming one) passes: there is nothing to compare. */
    fun longEnough(video: VideoItem, minMinutes: Int): Boolean = video.duration <= 0 || video.duration >= minMinutes * 60L

    fun allows(item: Item, settings: AppSettings, watched: Set<String>): Boolean = item !is VideoItem || (
        !(settings.hideWatchedRecommended && item.id in watched) &&
            !(settings.hideStreams && (item.isLive || item.upcoming)) &&
            longEnough(item, settings.minDurationMinutes)
        )
}

@Serializable
data class SeenVideo(val times: Int, val lastAt: Long)

/** How many separate times Home showed each video that was not opened since. */
@Serializable
data class SeenLog(val videos: Map<String, SeenVideo> = emptyMap()) {
    /** Counts [ids] as shown at [now]; showings closer together than [COUNT_EVERY_MS] are one. */
    fun record(ids: Collection<String>, now: Long): SeenLog {
        val changed = ids.filter { id -> videos[id]?.let { now - it.lastAt >= COUNT_EVERY_MS } != false }
        if (changed.isEmpty()) return this
        val updated = videos + changed.associateWith { id -> SeenVideo((videos[id]?.times ?: 0) + 1, now) }
        return SeenLog(
            updated.filterValues { now - it.lastAt < KEEP_MS }.entries
                .sortedByDescending { it.value.lastAt }.take(MAX_VIDEOS).associate { it.toPair() },
        )
    }

    /** Moves the videos shown [TIRED_AFTER] times to the end of [items], one loaded page: what is on screen never jumps. */
    fun demote(items: List<Item>): List<Item> {
        val (tired, fresh) = items.partition { it is VideoItem && (videos[it.id]?.times ?: 0) >= TIRED_AFTER }
        return fresh + tired
    }

    companion object {
        const val TIRED_AFTER = 3
        const val COUNT_EVERY_MS = 6 * 60 * 60 * 1000L
        const val KEEP_MS = 30 * 24 * 60 * 60 * 1000L
        const val MAX_VIDEOS = 3000
    }
}

/** The [SeenLog] of this phone. It stays here: not in a backup, and YouTube never learns of it. */
object SeenVideos {
    private val state = MutableStateFlow(SeenLog())
    private val disk = JsonFile("SeenVideos", SeenLog.serializer()) { state.value }

    val log: SeenLog get() = state.value

    fun init(context: Context) {
        disk.load(File(context.filesDir, "seen.json")) { fromFile ->
            state.update { current -> SeenLog(fromFile?.videos.orEmpty() + current.videos) }
        }
    }

    fun record(ids: Collection<String>, now: Long) = change { it.record(ids, now) }

    /** An opened video starts over: it was not ignored. */
    fun forget(id: String) = change { if (id in it.videos) SeenLog(it.videos - id) else it }

    fun clear() = change { SeenLog() }

    private fun change(transform: (SeenLog) -> SeenLog) {
        val before = state.value
        state.update(transform)
        if (state.value != before) disk.save()
    }
}
