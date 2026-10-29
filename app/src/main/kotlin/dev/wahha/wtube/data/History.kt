package dev.wahha.wtube.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * [finished] once the video was played to (near) its end; rewatching does not take it back. [positionAt]
 * is when [positionMs] was saved (0 in entries from before 0.12).
 */
@Serializable
data class HistoryEntry(
    val video: VideoItem,
    val watchedAt: Long,
    val positionMs: Long = 0,
    val finished: Boolean = false,
    val positionAt: Long = 0,
)

/**
 * Local watch history with resume positions, newest first, in one small JSON file. Account history
 * comes with login (stage 5); this one works without it and feeds the Library tab.
 */
object History {
    private const val MAX_ENTRIES = 300
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val disk = JsonFile("History", ListSerializer(HistoryEntry.serializer())) { state.value }

    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    private val loadedState = MutableStateFlow(false)

    /** False until the file is read: an empty [entries] before that means "unknown", not "nothing watched". */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    fun init(context: Context) {
        disk.load(File(context.filesDir, "history.json")) { fromFile ->
            // Entries recorded before the file finished loading go on top.
            state.update { current -> (current + fromFile.orEmpty()).distinctBy { it.video.id } }
            loadedState.value = true
        }
    }

    fun record(video: VideoItem) {
        if (Settings.current.value.saveHistory) recordNow(video)
    }

    private fun recordNow(video: VideoItem) = change { list ->
        val before = list.firstOrNull { it.video.id == video.id }
        (listOf(HistoryEntry(video, System.currentTimeMillis(), before?.positionMs ?: 0, before?.finished ?: false, before?.positionAt ?: 0)) + list.filter { it.video.id != video.id })
            .take(MAX_ENTRIES)
    }

    fun savePosition(id: String, positionMs: Long, finished: Boolean = false) = change { list ->
        if (!Settings.current.value.saveHistory) return@change list
        val now = System.currentTimeMillis()
        list.map { if (it.video.id == id) it.copy(positionMs = positionMs, finished = it.finished || finished, positionAt = now) else it }
    }

    fun entryOf(id: String): HistoryEntry? = state.value.firstOrNull { it.video.id == id }

    fun clear() = change { emptyList() }

    /** The whole history at once (a restored backup). */
    fun replace(entries: List<HistoryEntry>) = change { entries.distinctBy { it.video.id }.take(MAX_ENTRIES) }

    private fun change(transform: (List<HistoryEntry>) -> List<HistoryEntry>) {
        state.update(transform)
        disk.save()
    }
}
