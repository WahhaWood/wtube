package dev.wahha.wtube.dpi

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/** YouTube and the Google hosts it loads from; only these go through the bypass, everything else stays direct. */
private val BypassDomains = listOf(
    "youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com", "ytimg.com", "ggpht.com",
    "googleusercontent.com", "googleapis.com", "google.com", "gstatic.com",
)

/** The [BypassDomains] entry [host] belongs to, or null. */
fun bypassDomain(host: String): String? {
    val name = host.lowercase().trimEnd('.')
    return BypassDomains.firstOrNull { name == it || name.endsWith(".$it") }
}

fun isBypassHost(host: String): Boolean = bypassDomain(host) != null

/**
 * Routes [isBypassHost] requests through [proxy] when one is set, the rest directly. One instance per route
 * (see [dev.wahha.wtube.data.Http.setRoute]).
 */
class BypassSelector(val proxy: Proxy?) : ProxySelector() {
    override fun select(uri: URI): List<Proxy> = route(uri.host, proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

/**
 * The proxy alone for bypass hosts, never direct as a second choice: OkHttp races its routes (fast fallback
 * starts the next one when the first has not connected in 250 ms, and byedpi answers only after its own DNS
 * lookup and connect). A direct connection to googlevideo that wins gets through the handshake, then the
 * censor throttles it, and the pooled connection stalls every video after it (0.20.1 on LTE in Russia).
 * If the proxy's process dies, [Dpi] routes directly by itself until it is back.
 */
fun route(host: String?, proxy: Proxy?): List<Proxy> =
    if (proxy != null && host != null && isBypassHost(host)) listOf(proxy) else listOf(Proxy.NO_PROXY)

/** The local byedpi proxy on [port]. */
fun localSocks(port: Int) = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))

/**
 * A user's own proxy from "host:port", optionally with a `socks5://` or `http://` scheme (SOCKS by
 * default); null if it does not parse.
 */
fun parseProxy(text: String): Proxy? {
    val trimmed = text.trim()
    val scheme = trimmed.substringBefore("://", "").lowercase()
    val type = when (scheme) {
        "", "socks", "socks5" -> Proxy.Type.SOCKS
        "http" -> Proxy.Type.HTTP
        else -> return null
    }
    val address = trimmed.substringAfter("://").trimEnd('/')
    val host = address.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    val port = address.substringAfterLast(':', "").toIntOrNull() ?: return null
    if (host.isBlank() || host.any { it.isWhitespace() || it == '/' } || port !in 1..65535) return null
    return Proxy(type, InetSocketAddress.createUnresolved(host, port))
}
