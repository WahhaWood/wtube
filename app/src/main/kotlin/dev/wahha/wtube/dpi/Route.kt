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

fun isBypassHost(host: String): Boolean {
    val name = host.lowercase().trimEnd('.')
    return BypassDomains.any { name == it || name.endsWith(".$it") }
}

/**
 * Routes [isBypassHost] requests through [proxy] when one is set, the rest directly. Direct is the second
 * choice for bypass hosts too: OkHttp falls back to it if the local proxy is gone (its process died).
 */
object BypassSelector : ProxySelector() {
    @Volatile
    var proxy: Proxy? = null

    override fun select(uri: URI): List<Proxy> = route(uri.host, proxy)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
}

fun route(host: String?, proxy: Proxy?): List<Proxy> =
    if (proxy != null && host != null && isBypassHost(host)) listOf(proxy, Proxy.NO_PROXY) else listOf(Proxy.NO_PROXY)

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
