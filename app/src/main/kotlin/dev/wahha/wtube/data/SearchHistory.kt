package dev.wahha.wtube.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Past search queries, newest first, in a small JSON file next to [History]; kept only while history is on. */
object SearchHistory {
    private const val MAX_ENTRIES = 50
    private val state = MutableStateFlow<List<String>>(emptyList())
    private val disk = JsonFile("SearchHistory", ListSerializer(String.serializer())) { state.value }

    val queries: StateFlow<List<String>> = state.asStateFlow()

    fun init(context: Context) {
        disk.load(File(context.filesDir, "search_history.json")) { fromFile ->
            state.update { current -> (current + fromFile.orEmpty()).distinctBy { it.lowercase() } }
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
        disk.save()
    }
}
