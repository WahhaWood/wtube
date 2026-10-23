package dev.wahha.wtube.dpi

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** What the bypass remembers on this phone only (not in backups: networks and strategies are this phone's). */
data class DpiMemory(
    /** Network key -> strategy id, or [DIRECT] when the network needs no bypass. */
    val networks: Map<String, String> = emptyMap(),
    /** Strategies that worked lately, newest first. */
    val recent: List<String> = emptyList(),
    /** The last search's results by strategy id, for the manual list. */
    val results: Map<String, ProbeResult> = emptyMap(),
    /** The user's own byedpi arguments; replace the presets when set. */
    val customArgs: String = "",
    /** The user's own proxy ("host:port"); replaces byedpi when set. */
    val customProxy: String = "",
    val presetsEtag: String = "",
    val presetsCheckedAt: Long = 0,
) {
    companion object {
        const val DIRECT = "direct"
        const val RECENT_SIZE = 5
    }
}

class DpiStore(context: Context) {
    private val store = context.applicationContext.dpiStore
    private val json = Json { ignoreUnknownKeys = true }

    val memory: Flow<DpiMemory> = store.data.map { prefs ->
        DpiMemory(
            networks = prefs.asMap().mapNotNull { (key, value) ->
                if (key.name.startsWith(NET_PREFIX) && value is String) key.name.removePrefix(NET_PREFIX) to value else null
            }.toMap(),
            recent = prefs[Recent]?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            results = prefs[Results]?.let { runCatching { json.decodeFromString<Map<String, ProbeResult>>(it) }.getOrNull() }.orEmpty(),
            customArgs = prefs[CustomArgs].orEmpty(),
            customProxy = prefs[CustomProxy].orEmpty(),
            presetsEtag = prefs[PresetsEtag].orEmpty(),
            presetsCheckedAt = prefs[PresetsCheckedAt] ?: 0,
        )
    }

    /** Remembers [choice] (a strategy id or [DpiMemory.DIRECT]) for [network]; a strategy also goes to the front of the recent ones. */
    suspend fun remember(network: String, choice: String) = store.edit { prefs ->
        prefs[stringPreferencesKey(NET_PREFIX + network)] = choice
        if (choice != DpiMemory.DIRECT) {
            val recent = listOf(choice) + prefs[Recent]?.split(',').orEmpty().filter { it.isNotBlank() && it != choice }
            prefs[Recent] = recent.take(DpiMemory.RECENT_SIZE).joinToString(",")
        }
    }

    /**
     * 0.20.0 counted a bare googlevideo answer as a working route and remembered strategies that stall video:
     * its choices, recent list and results are dropped once, so every network is searched again.
     */
    suspend fun migrate() = store.edit { prefs ->
        if ((prefs[Version] ?: 1) >= VERSION) return@edit
        prefs.asMap().keys.filter { it.name.startsWith(NET_PREFIX) }.forEach { prefs.remove(it) }
        prefs.remove(Recent)
        prefs.remove(Results)
        prefs[Version] = VERSION
    }

    suspend fun forget(network: String) = store.edit { it.remove(stringPreferencesKey(NET_PREFIX + network)) }

    suspend fun saveResults(results: Map<String, ProbeResult>) = store.edit { it[Results] = json.encodeToString(results) }

    suspend fun setCustomArgs(args: String) = store.edit { it[CustomArgs] = args.trim() }

    suspend fun setCustomProxy(proxy: String) = store.edit { it[CustomProxy] = proxy.trim() }

    suspend fun presetsChecked(etag: String?, at: Long) = store.edit { prefs ->
        if (etag != null) prefs[PresetsEtag] = etag
        prefs[PresetsCheckedAt] = at
    }

    private companion object {
        const val NET_PREFIX = "net:"
        const val VERSION = 2
        val Version = intPreferencesKey("version")
        val Recent = stringPreferencesKey("recent")
        val Results = stringPreferencesKey("results")
        val CustomArgs = stringPreferencesKey("custom_args")
        val CustomProxy = stringPreferencesKey("custom_proxy")
        val PresetsEtag = stringPreferencesKey("presets_etag")
        val PresetsCheckedAt = longPreferencesKey("presets_checked_at")
    }
}

private val Context.dpiStore by preferencesDataStore("dpi", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })
