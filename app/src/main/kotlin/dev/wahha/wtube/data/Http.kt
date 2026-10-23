package dev.wahha.wtube.data

import dev.wahha.wtube.dpi.BypassSelector
import dev.wahha.wtube.dpi.Dpi
import dev.wahha.wtube.dpi.isBypassHost
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** One OkHttp client (one connection pool) for the extractor, the player and image loading. */
object Http {
    // Same desktop Firefox UA as NewPipe's DownloaderImpl: YouTube serves the web layouts the extractor parses.
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    /** Settings shared by every route; not tied to one, so nothing a route change cancels runs on it. */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .eventListener(BypassWatch)
            .build()
    }

    @Volatile
    private var current: OkHttpClient? = null

    /**
     * The client on the current route: YouTube's hosts through the blocking bypass when it runs (dpi/).
     * Take it for each request and do not keep it, a route change replaces it; holders of a call factory use [calls].
     */
    val client: OkHttpClient
        get() = current ?: synchronized(this) { current ?: onRoute(null).also { current = it } }

    /** Calls on whatever route is current when each one is made, for the player, downloads and image loading. */
    val calls: Call.Factory = Call.Factory { client.newCall(it) }

    /** The proxy YouTube's hosts go through now; null = directly. */
    val route: Proxy? get() = (client.proxySelector as BypassSelector).proxy

    /**
     * Sends YouTube's hosts through [proxy] (null = directly) from now on and returns the previous route's
     * client, null if the route did not change. The new route has a client of its own, so no new request
     * rides a connection made on the old one (OkHttp pools by address, and the selector is part of it).
     * The caller cancels the old client's calls and closes its idle connections, off the main thread.
     */
    fun setRoute(proxy: Proxy?): OkHttpClient? = synchronized(this) {
        val old = client
        if ((old.proxySelector as BypassSelector).proxy == proxy) return null
        current = onRoute(proxy)
        old
    }

    // Its own dispatcher, so that cancelling the old route's calls leaves the new route's alone.
    private fun onRoute(proxy: Proxy?): OkHttpClient {
        val selector = BypassSelector(proxy)
        return base.newBuilder().proxySelector(selector).dispatcher(Dispatcher()).addInterceptor(RouteGate(selector)).build()
    }

    /** Calls [RouteGate] holds or has moved to a newer route: not the old route's to cancel. */
    private val held = ConcurrentHashMap.newKeySet<Call>()

    fun isHeld(call: Call): Boolean = call in held

    /**
     * Holds YouTube requests made right after launch until the bypass has its first route, and moves a
     * request whose client's route has been replaced since (it waited here, or queued) onto the current one.
     */
    private class RouteGate(private val selector: BypassSelector) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!isBypassHost(request.url.host)) return chain.proceed(request)
            val call = chain.call()
            held += call
            val now = try {
                Dpi.awaitFirstRoute()
                client
            } catch (e: Throwable) {
                held -= call
                throw e
            }
            if (now.proxySelector === selector) {
                held -= call
                return chain.proceed(request)
            }
            try {
                return now.newCall(request).execute()
            } finally {
                held -= call
            }
        }
    }

    /** Tells the bypass about YouTube requests that fail on the network, so it can re-check its strategy. */
    private object BypassWatch : EventListener() {
        override fun callFailed(call: Call, ioe: IOException) {
            if (!call.isCanceled()) Dpi.onRequestFailed(call.request().url.host)
        }

        override fun callEnd(call: Call) = Dpi.onRequestSucceeded(call.request().url.host)

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) =
            Dpi.onConnected(call.request().url.host, proxy)
    }
}
