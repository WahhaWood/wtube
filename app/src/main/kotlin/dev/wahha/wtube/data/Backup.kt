package dev.wahha.wtube.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What survives a reinstall or a new phone through a file: settings, watch history with positions, past
 * searches and what is hidden from recommendations. Not the account (cookies stay sealed on this phone)
 * and not downloads (they are files).
 */
@Serializable
class Backup(
    val version: Int = VERSION,
    val settings: AppSettings,
    val history: List<HistoryEntry>,
    val searches: List<String> = emptyList(),
    val hidden: HiddenItems = HiddenItems(),
) {
    companion object {
        const val VERSION = 1
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun current(): String = json.encodeToString(serializer(), Backup(settings = Settings.current.value, history = History.entries.value, searches = SearchHistory.queries.value, hidden = Hidden.items.value))

        fun parse(text: String): Backup = json.decodeFromString(serializer(), text)

        /** Replaces settings, history, searches and the hidden list with the backup's. */
        suspend fun restore(backup: Backup) {
            Settings.restore(backup.settings)
            History.replace(backup.history)
            SearchHistory.replace(backup.searches)
            Hidden.replace(backup.hidden)
        }
    }
}
