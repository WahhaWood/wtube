package dev.wahha.wtube.data

import dev.wahha.wtube.dpi.BypassSelector
import dev.wahha.wtube.dpi.Dpi
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One OkHttp client (one connection pool) for the extractor, the player and image loading. */
object Http {
    // Same desktop Firefox UA as NewPipe's DownloaderImpl: YouTube serves the web layouts the extractor parses.
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // YouTube's hosts through the blocking bypass when it runs (dpi/).
            .proxySelector(BypassSelector)
            .eventListener(BypassWatch)
            .build()
    }

    /** Tells the bypass about YouTube requests that fail on the network, so it can re-check its strategy. */
    private object BypassWatch : EventListener() {
        override fun callFailed(call: Call, ioe: IOException) {
            if (!call.isCanceled()) Dpi.onRequestFailed(call.request().url.host)
        }

        override fun callEnd(call: Call) = Dpi.onRequestSucceeded(call.request().url.host)
    }
}
