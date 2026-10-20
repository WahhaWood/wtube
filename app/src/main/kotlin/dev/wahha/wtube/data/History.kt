package dev.wahha.wtube.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** [finished] once the video was played to (near) its end; rewatching does not take it back. */
@Serializable
data class HistoryEntry(val video: VideoItem, val watchedAt: Long, val positionMs: Long = 0, val finished: Boolean = false)

/**
 * Local watch history with resume positions, newest first, in one small JSON file. Account history
 * comes with login (stage 5); this one works without it and feeds the Library tab.
 */
object History {
    private const val MAX_ENTRIES = 300
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private var file: File? = null

    val entries: StateFlow<List<HistoryEntry>> = state.asStateFlow()

    private val loadedState = MutableStateFlow(false)

    /** False until the file is read: an empty [entries] before that means "unknown", not "nothing watched". */
    val loaded: StateFlow<Boolean> = loadedState.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "history.json").also { file = it }
        scope.launch {
            val fromFile = runCatching { if (f.exists()) json.decodeFromString<List<HistoryEntry>>(f.readText()) else emptyList() }
                .onFailure { Log.e("History", "Unreadable history, starting fresh", it) }
                .getOrDefault(emptyList())
            // Entries recorded before the file finished loading go on top.
            state.update { current -> (current + fromFile).distinctBy { it.video.id } }
            loadedState.value = true
        }
    }

    fun record(video: VideoItem) {
        if (Settings.current.value.saveHistory) recordNow(video)
    }

    private fun recordNow(video: VideoItem) = change { list ->
        val before = list.firstOrNull { it.video.id == video.id }
        (listOf(HistoryEntry(video, System.currentTimeMillis(), before?.positionMs ?: 0, before?.finished ?: false)) + list.filter { it.video.id != video.id })
            .take(MAX_ENTRIES)
    }

    fun savePosition(id: String, positionMs: Long, finished: Boolean = false) = change { list ->
        list.map { if (it.video.id == id) it.copy(positionMs = positionMs, finished = it.finished || finished) else it }
    }

    fun positionOf(id: String): Long = state.value.firstOrNull { it.video.id == id }?.positionMs ?: 0

    fun clear() = change { emptyList() }

    /** The whole history at once (a restored backup). */
    fun replace(entries: List<HistoryEntry>) = change { entries.distinctBy { it.video.id }.take(MAX_ENTRIES) }

    private fun change(transform: (List<HistoryEntry>) -> List<HistoryEntry>) {
        state.update(transform)
        val snapshot = state.value
        val f = file ?: return
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(f)
            }.onFailure { Log.e("History", "Could not save history", it) }
        }
    }
}
