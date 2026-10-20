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
import kotlinx.serialization.json.Json
import java.io.File

/** Past search queries, newest first, in a small JSON file next to [History]; kept only while history is on. */
object SearchHistory {
    private const val MAX_ENTRIES = 50
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<String>>(emptyList())
    private var file: File? = null

    val queries: StateFlow<List<String>> = state.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "search_history.json").also { file = it }
        scope.launch {
            val fromFile = runCatching { if (f.exists()) Json.decodeFromString<List<String>>(f.readText()) else emptyList() }
                .onFailure { Log.e("SearchHistory", "Unreadable search history, starting fresh", it) }
                .getOrDefault(emptyList())
            state.update { current -> (current + fromFile).distinctBy { it.lowercase() } }
        }
    }

    fun record(query: String) {
        if (!Settings.current.value.saveHistory) return
        change { list -> (listOf(query) + list.filterNot { it.equals(query, ignoreCase = true) }).take(MAX_ENTRIES) }
    }

    fun remove(query: String) = change { list -> list - query }

    fun clear() = change { emptyList() }

    /** All queries at once (a restored backup). */
    fun replace(queries: List<String>) = change { queries.distinctBy { it.lowercase() }.take(MAX_ENTRIES) }

    /** Past queries that start with what is typed (all of them for an empty field). */
    fun matching(typed: String): List<String> {
        val prefix = typed.trim()
        if (prefix.isEmpty()) return state.value
        return state.value.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
    }

    private fun change(transform: (List<String>) -> List<String>) {
        state.update(transform)
        val snapshot = state.value
        val f = file ?: return
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(Json.encodeToString(snapshot))
                tmp.renameTo(f)
            }.onFailure { Log.e("SearchHistory", "Could not save search history", it) }
        }
    }
}
