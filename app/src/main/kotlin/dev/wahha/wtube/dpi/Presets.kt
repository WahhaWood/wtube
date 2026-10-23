package dev.wahha.wtube.dpi

import android.content.Context
import android.util.Log
import dev.wahha.wtube.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.Proxy

/** The strategy presets: bundled `strategies.json` (from `dpi/` in the repository) or a newer copy downloaded from it. */
object Presets {
    private const val URL = "https://raw.githubusercontent.com/WahhaWood/wtube/main/dpi/strategies.json"
    private const val ASSET = "strategies.json"
    const val EVERY_MS = 24 * 60 * 60 * 1000L
    private const val TAG = "Dpi"

    private fun cache(context: Context) = File(context.filesDir, "dpi-strategies.json")

    /** The newer of the bundled list and the downloaded one; never throws (an empty list keeps the bypass idle). */
    fun load(context: Context): StrategyList {
        // The bundled list came with this very build, so its minApp is not checked.
        val bundled = runCatching { context.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            ?.let { Strategies.parse(it, Int.MAX_VALUE) }
        val downloaded = runCatching { cache(context).readText() }.getOrNull()?.let { Strategies.parse(it, BuildConfig.VERSION_CODE) }
        if (bundled == null) Log.e(TAG, "Bundled $ASSET does not parse")
        return listOfNotNull(bundled, downloaded).maxByOrNull { it.version } ?: StrategyList(0, strategies = emptyList())
    }

    sealed interface Refresh {
        data class Updated(val list: StrategyList) : Refresh
        data object Unchanged : Refresh
        data object Failed : Refresh
    }

    /** Asks GitHub for the list (directly, then through [proxy] if given), with the [etag] of the last download. */
    suspend fun refresh(context: Context, current: StrategyList, etag: String, proxy: Proxy?): Pair<Refresh, String?> =
        withContext(Dispatchers.IO) {
            val routes = listOfNotNull(Proxy.NO_PROXY, proxy)
            for (route in routes) {
                try {
                    return@withContext fetch(context, Probe.clientFor(route), current, etag)
                } catch (e: IOException) {
                    Log.w(TAG, "Presets: ${e.javaClass.simpleName} ${if (route == Proxy.NO_PROXY) "directly" else "through the bypass"}")
                }
            }
            Refresh.Failed to null
        }

    private fun fetch(context: Context, client: OkHttpClient, current: StrategyList, etag: String): Pair<Refresh, String?> {
        val request = Request.Builder().url(URL).apply { if (etag.isNotEmpty()) header("If-None-Match", etag) }.build()
        client.newCall(request).execute().use { response ->
            if (response.code == 304) return Refresh.Unchanged to etag
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val text = response.body.string()
            val list = Strategies.parse(text, BuildConfig.VERSION_CODE) ?: return Refresh.Failed to null
            val newEtag = response.header("ETag")
            if (list.version <= current.version) return Refresh.Unchanged to newEtag
            cache(context).writeText(text)
            return Refresh.Updated(list) to newEtag
        }
    }
}
