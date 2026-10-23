package dev.wahha.wtube.data

import java.net.Proxy
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * googlevideo servers a stream can come from. Every stream link names two (`mn`): the one in its host and a
 * spare one (`rr<fvip>---<second mn>`), which serves the same link as is; YouTube's own player moves there
 * when the first fails. A blocking bypass may get through to one and not the other (0.20.2: the test video
 * loaded, the friend's video never connected), so a server that could not be reached on the current route
 * sends the player's next requests to the spare.
 */
object StreamServers {
    private const val FORGET_MS = 10 * 60_000L

    private class Failure(val route: Proxy?, val at: Long)

    private val failed = ConcurrentHashMap<String, Failure>()

    /** [url] on its spare server, or null if the link names none. */
    fun spareUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        if (!host.endsWith(".googlevideo.com")) return null
        val query = uri.rawQuery?.split('&')?.associate { it.substringBefore('=') to it.substringAfter('=', "") } ?: return null
        val servers = URLDecoder.decode(query["mn"].orEmpty(), "UTF-8").split(',').filter { it.isNotBlank() }
        val spare = servers.getOrNull(1) ?: return null
        val number = query["fvip"]?.toIntOrNull() ?: return null
        val spareHost = "rr$number---$spare.googlevideo.com"
        if (spareHost == host) return null
        return url.replaceFirst("//$host", "//$spareHost")
    }

    /** A connection to [host] failed on [route] (null = direct). */
    fun onConnectFailed(host: String, route: Proxy?, now: Long = System.currentTimeMillis()) {
        if (host.endsWith(".googlevideo.com")) failed[host] = Failure(route, now)
    }

    fun onConnected(host: String) {
        failed.remove(host)
    }

    /**
     * Where to ask for [url] on [route]: its own server, unless that failed lately on this route and the spare
     * did not. Each failure flips the next try to the other server, so neither is given up for good.
     */
    fun pick(url: String, route: Proxy?, now: Long = System.currentTimeMillis()): String {
        val host = runCatching { URI(url).host }.getOrNull() ?: return url
        val own = downSince(host, route, now) ?: return url
        val spare = spareUrl(url) ?: return url
        val other = downSince(URI(spare).host, route, now) ?: return spare
        // Both failed: the one that failed longer ago.
        return if (own <= other) url else spare
    }

    /** When [host] last failed on [route], if lately; null = up. */
    private fun downSince(host: String, route: Proxy?, now: Long): Long? =
        failed[host]?.takeIf { it.route == route && now - it.at < FORGET_MS }?.at

}
