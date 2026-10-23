package dev.wahha.wtube

import dev.wahha.wtube.data.Http
import dev.wahha.wtube.dpi.BypassSelector
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import kotlin.concurrent.thread

/**
 * How the app's client really routes YouTube through a local SOCKS proxy that, like byedpi, answers only
 * after its own lookup and connect. "Direct" is a server at the address YouTube's name resolves to here.
 */
class BypassRouteTest {
    private val direct = MockWebServer().apply {
        repeat(4) { enqueue(MockResponse().setBody("direct")) }
        start()
    }
    private val proxied = MockWebServer().apply {
        repeat(4) { enqueue(MockResponse().setBody("proxy")) }
        start()
    }
    private val socks = SlowSocks(proxied.port, answerAfterMs = 600)
    private val url = "http://www.youtube.com:${direct.port}/"
    private val loopback = Dns { listOf(InetAddress.getLoopbackAddress()) }

    @After
    fun tearDown() {
        socks.close()
        direct.shutdown()
        proxied.shutdown()
    }

    private fun client(selector: ProxySelector): OkHttpClient = Http.base.newBuilder().dns(loopback).proxySelector(selector).build()

    private fun OkHttpClient.get(): String = newCall(Request.Builder().url(url).build()).execute().use { it.body.string() }

    @Test
    fun aSlowProxyIsNotRacedByADirectConnection() {
        // What 0.20.1 did: direct as the second choice. OkHttp starts it after 250 ms and the faster one wins.
        val withFallback = object : ProxySelector() {
            override fun select(uri: URI) = listOf(socks.proxy, Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        }
        assertEquals("direct", client(withFallback).get())

        assertEquals("proxy", client(BypassSelector(socks.proxy)).get())
    }

    @Test
    fun aNewRouteDoesNotRideTheOldRoutesConnections() {
        // Both share Http's pool, as the route clients do; the kept-alive direct connection must not be reused.
        assertEquals("direct", client(BypassSelector(null)).get())
        assertEquals("proxy", client(BypassSelector(socks.proxy)).get())
        assertEquals(1, direct.requestCount)
    }

    @Test
    fun aRequestOnAReplacedRouteMovesToTheCurrentOne() {
        val old = Http.client
        Http.setRoute(socks.proxy)
        try {
            assertEquals("proxy", old.get())
            assertEquals(0, direct.requestCount)
        } finally {
            Http.setRoute(null)
        }
    }

    /** A SOCKS5 server that tunnels every connection to [target] and answers CONNECT after [answerAfterMs]. */
    private class SlowSocks(private val target: Int, private val answerAfterMs: Long) {
        private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort))

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) = try {
            val input = client.getInputStream()
            val output = client.getOutputStream()
            input.readN(2).let { input.readN(it[1].toInt()) }
            output.write(byteArrayOf(5, 0))
            val head = input.readN(4)
            when (head[3].toInt()) {
                1 -> input.readN(4 + 2)
                3 -> input.readN(input.read() + 2)
                4 -> input.readN(16 + 2)
            }
            Thread.sleep(answerAfterMs)
            val upstream = Socket(InetAddress.getLoopbackAddress(), target)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            thread(isDaemon = true) { pipe(upstream.getInputStream(), output) }
            pipe(input, upstream.getOutputStream())
        } catch (_: Exception) {
            client.close()
        }

        private fun pipe(from: InputStream, to: OutputStream) = try {
            from.copyTo(to)
        } catch (_: IOException) {
        }

        private fun InputStream.readN(n: Int): ByteArray = ByteArray(n).also { buffer ->
            var read = 0
            while (read < n) read += read(buffer, read, n - read).also { if (it < 0) throw IOException("closed") }
        }

        fun close() = server.close()
    }
}
