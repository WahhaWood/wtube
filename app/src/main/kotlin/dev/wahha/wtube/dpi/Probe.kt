package dev.wahha.wtube.dpi

import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.NewPipeDownloader
import dev.wahha.wtube.data.StreamServers
import dev.wahha.wtube.player.YoutubeRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** What one route (direct or a strategy) managed: the site, images, and a real video stream with its speed. */
@Serializable
data class ProbeResult(
    val site: Boolean,
    val images: Boolean,
    /** A real stream gave at least [MIN_VIDEO_BYTES]: a handshake or an empty answer from googlevideo does not count. */
    val video: Boolean,
    val kbps: Int = 0,
    /** Why the video part failed, for the log: no stream link, an HTTP code, or how much came before it stalled. */
    val note: String = "",
    /**
     * The stream's spare server ([StreamServers]) gave video too; null = not tried (no spare, or the first
     * server failed). A route that reaches only one server leaves the videos on the other stalled (0.20.2).
     */
    val spare: Boolean? = null,
) {
    val works get() = site && images && video
    /** Works and plays 1080p without stalling, from either of the stream's servers. */
    val fast get() = works && kbps >= FAST_KBPS && spare != false
    val score get() = (if (site) 1 else 0) + (if (images) 1 else 0) + (if (video) 2 else 0)

    companion object {
        const val FAST_KBPS = 2_000
        val Failed = ProbeResult(site = false, images = false, video = false)
    }
}

/**
 * Checks YouTube through one route. A TLS handshake alone is not enough: censors let the handshake through
 * and then stall or throttle googlevideo, so the check reads [VIDEO_BYTES] of a real stream and times it.
 */
class Probe(
    private val siteUrl: String = "https://www.youtube.com/generate_204",
    private val imageUrl: String = "https://i.ytimg.com/vi/$TEST_VIDEO/mqdefault.jpg",
    /** A googlevideo stream URL through the given client; null if it could not be had. */
    private val streamUrl: suspend (OkHttpClient) -> String? = ::extractStreamUrl,
) {
    private val streamLock = Mutex()
    private var stream: String? = null

    /** [proxy] null = directly. */
    suspend fun check(proxy: Proxy?): ProbeResult = withContext(Dispatchers.IO) { checkOn(clientFor(proxy)) }

    /** The way the app's own requests go: its route choice per host, not one proxy set by hand. */
    suspend fun checkRouted(): ProbeResult = withContext(Dispatchers.IO) { checkOn(limits(Http.client.newBuilder().dispatcher(Dispatcher()))) }

    private suspend fun checkOn(client: OkHttpClient): ProbeResult {
        try {
            val site = fetch(client, Request.Builder().url(siteUrl).build()) { code, _ -> code == 204 || code == 200 }
            if (!site) return ProbeResult.Failed
            val images = fetch(client, Request.Builder().url(imageUrl).build()) { code, bytes -> code == 200 && bytes > 0 }
            // Without a stream link the app could not play anything on this route either, so it does not work.
            // (Until 0.20.0 a bare googlevideo handshake counted, and a route that stalls video got picked.)
            val url = streamLock.withLock {
                stream ?: withTimeoutOrNull(STREAM_URL_TIMEOUT_MS) { runCatching { streamUrl(extractorClient(client)) }.getOrNull() }?.also { stream = it }
            } ?: return ProbeResult(site, images, video = false, note = "no stream link")
            val (kbps, note) = videoSpeed(client, url)
            val spare = if (kbps == null) null else StreamServers.spareUrl(url)?.let { videoSpeed(client, it).first != null }
            return ProbeResult(site, images, video = kbps != null, kbps = kbps ?: 0, note = note, spare = spare)
        } finally {
            client.connectionPool.evictAll()
        }
    }

    /** Kbit/s over the first [VIDEO_BYTES] of [url] (null if too little came within the time limit), and what happened. */
    private suspend fun videoSpeed(client: OkHttpClient, url: String): Pair<Int?, String> = runInterruptible(Dispatchers.IO) {
        val started = System.nanoTime()
        var read = 0L
        var error = ""
        try {
            client.newCall(rangeRequest(url)).execute().use { response ->
                if (!response.isSuccessful) return@runInterruptible null to "HTTP ${response.code}"
                val source = response.body.source()
                val buffer = ByteArray(64 * 1024)
                val input = source.inputStream()
                while (read < VIDEO_BYTES && System.nanoTime() - started < VIDEO_TIME_LIMIT_NS) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    read += n
                }
            }
        } catch (e: IOException) {
            error = " (${e.javaClass.simpleName})"
        }
        val ms = (System.nanoTime() - started) / 1_000_000
        val note = "${read / 1024} KB in $ms ms$error"
        (if (read < MIN_VIDEO_BYTES) null else (read * 8 / ms.coerceAtLeast(1)).toInt()) to note
    }

    private suspend fun fetch(client: OkHttpClient, request: Request, ok: (Int, Long) -> Boolean): Boolean =
        runInterruptible(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response -> ok(response.code, response.body.bytes().size.toLong()) }
            } catch (_: IOException) {
                false
            }
        }

    companion object {
        /** Big Buck Bunny on the Blender channel: long-lived, public, has large streams. */
        const val TEST_VIDEO = "aqz-KE-bpKQ"
        const val VIDEO_BYTES = 1L shl 20
        const val MIN_VIDEO_BYTES = 256L * 1024
        private const val VIDEO_TIME_LIMIT_NS = 8_000_000_000L
        // The extractor makes several requests, one of them the ~2.5 MB player script: slow over a bypass on LTE.
        private const val STREAM_URL_TIMEOUT_MS = 60_000L

        /** A client of its own for one route: an explicit proxy, a fresh pool, short timeouts, no error watching. */
        fun clientFor(proxy: Proxy?): OkHttpClient = limits(Http.base.newBuilder().proxy(proxy ?: Proxy.NO_PROXY))

        private fun limits(builder: OkHttpClient.Builder): OkHttpClient = builder
            .connectionPool(ConnectionPool())
            .eventListener(EventListener.NONE)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()

        /** [client] without the per-call limit, which the player script would not fit into on a slow network. */
        private fun extractorClient(client: OkHttpClient): OkHttpClient =
            client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

        fun rangeRequest(url: String): Request = YoutubeRequests.rangeRequest(url, 0, VIDEO_BYTES)

        /** Runs the extractor for [TEST_VIDEO] with every request on [client]; the largest video-only stream. */
        suspend fun extractStreamUrl(client: OkHttpClient): String? = runInterruptible(Dispatchers.IO) {
            NewPipeDownloader.clientOverride.set(client)
            try {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$TEST_VIDEO")
                    .videoOnlyStreams.filter { it.isUrl }.maxByOrNull { it.bitrate }?.content
            } finally {
                NewPipeDownloader.clientOverride.remove()
            }
        }
    }
}
