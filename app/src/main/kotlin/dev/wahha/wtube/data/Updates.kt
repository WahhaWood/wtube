package dev.wahha.wtube.data

import android.content.Context
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

/** A GitHub release newer than the installed build; [url] is the APK, or the release page without one. */
data class Release(val version: String, val url: String, val notes: String)

/** Looks for a newer WTube on GitHub Releases. Nothing is installed by itself: the browser downloads the APK. */
object Updates {
    private const val LATEST = "https://api.github.com/repos/WahhaWood/wtube/releases/latest"
    private const val EVERY_MS = 12 * 60 * 60 * 1000L

    private val state = MutableStateFlow<Release?>(null)

    /** The newer release to offer, until it is dismissed or the app restarts. */
    val available: StateFlow<Release?> = state.asStateFlow()

    /**
     * Asks GitHub at most every 12 hours unless [force]; a version the user has dismissed is not offered again.
     * Returns the newer release found (even a dismissed one when [force]), or null; network errors count as none.
     */
    suspend fun check(context: Context, installed: String, force: Boolean = false): Release? {
        val prefs = context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checked", 0) < EVERY_MS) return null
        val release = try {
            latest()
        } catch (_: IOException) {
            return null
        } catch (_: RuntimeException) {
            return null
        }
        prefs.edit().putLong("checked", now).apply()
        if (release == null || !isNewer(release.version, installed)) return null
        if (force || prefs.getString("dismissed", null) != release.version) state.value = release
        return release
    }

    fun dismiss(context: Context) {
        state.value?.let { context.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putString("dismissed", it.version).apply() }
        state.value = null
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST).header("Accept", "application/vnd.github+json").get().build()
        Http.client.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) throw IOException("GitHub: HTTP ${response.code}")
            parse(JsonParser.`object`().from(response.body.string()))
        }
    }

    fun parse(json: JsonObject): Release? {
        if (json.getBoolean("draft") || json.getBoolean("prerelease")) return null
        val version = json.getString("tag_name", "").removePrefix("v").ifEmpty { return null }
        val apk = json.getArray("assets").filterIsInstance<JsonObject>()
            .firstOrNull { it.getString("name", "").endsWith(".apk") }?.getString("browser_download_url")
        return Release(version, apk ?: json.getString("html_url", ""), json.getString("body", "").trim())
    }

    /** Dotted numbers compared part by part ("0.10.0" is newer than "0.9.2"); a part that is not a number counts as 0. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
