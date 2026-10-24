package dev.wahha.wtube

import dev.wahha.wtube.dpi.Backend
import dev.wahha.wtube.dpi.Relay
import dev.wahha.wtube.dpi.Resolver
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class RelayTest {
    private val closeables = mutableListOf<Closeable>()

    @After
    fun tearDown() = closeables.forEach { it.close() }

    /** A SOCKS5 server that answers the hello with "SH" unless [answer] says the address gets silence. */
    private inner class FakeBackend(private val answer: (String) -> Boolean) : Closeable {
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        val asked = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (true) {
                    val socket = try { server.accept() } catch (_: Exception) { break }
                    Thread { serve(socket) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            closeables += this
        }

        private fun serve(socket: Socket) {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                input.read(); repeat(input.read()) { input.read() }
                output.write(byteArrayOf(5, 0))
                val head = ByteArray(4).also { input.readNBytes(it, 0, 4) }
                val ip = InetAddress.getByAddress(ByteArray(if (head[3].toInt() == 1) 4 else 16).also { input.readNBytes(it, 0, it.size) })
                input.readNBytes(2)
                asked += ip.hostAddress!!
                output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                val buffer = ByteArray(1024)
                val n = input.read(buffer)
                if (n > 0) received += String(buffer, 0, n)
                if (answer(ip.hostAddress!!)) {
                    output.write("SH".toByteArray())
                    while (true) {
                        val m = input.read(buffer)
                        if (m < 0) break
                        output.write(buffer, 0, m)
                    }
                } else {
                    Thread.sleep(30_000)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { socket.close() }
            }
        }

        override fun close() = server.close()
    }

    private fun relay(
        backends: List<Backend>,
        addresses: List<String> = listOf("203.0.113.1"),
        hedgeMs: Long = 100,
    ): Relay {
        val resolver = Resolver(
            client = { OkHttpClient() },
            endpoints = emptyList(),
            system = { addresses.map { InetAddress.getByName(it) } },
        )
        return Relay(resolver, { backends }, hedgeMs = hedgeMs, attemptMs = 600, deadlineMs = 3_000).also { closeables += it }
    }

    /** SOCKS5 CONNECT to [host]:443 through [relay] by name, then the hello; returns the socket. */
    private fun connect(relay: Relay, host: String = "rr1---sn-test.googlevideo.com"): Socket {
        val socket = Socket("127.0.0.1", relay.port)
        socket.soTimeout = 5_000
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, 0))
        input.readNBytes(2)
        val name = host.toByteArray()
        output.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf(1, 0xBB.toByte()))
        input.readNBytes(10)
        output.write("HELLO".toByteArray())
        return socket
    }

    private fun InputStream.readText(n: Int) = String(readNBytes(n))

    @Test
    fun theHelloGoesThroughAndTheAnswerComesBack() {
        val backend = FakeBackend { true }
        val socket = connect(relay(listOf(Backend(backend.port, "a"))))
        assertEquals("SH", socket.getInputStream().readText(2))
        socket.getOutputStream().write("more".toByteArray())
        assertEquals("more", socket.getInputStream().readText(4))
        assertEquals(listOf("HELLO"), backend.received)
        assertEquals(listOf("203.0.113.1"), backend.asked)
    }

    @Test
    fun aSilentStrategyIsBackedUpByTheNextOneWithTheSameHello() {
        val silent = FakeBackend { false }
        val good = FakeBackend { true }
        val relay = relay(listOf(Backend(silent.port, "silent"), Backend(good.port, "good")))
        val socket = connect(relay)
        assertEquals("SH", socket.getInputStream().readText(2))
        assertEquals(listOf("HELLO"), good.received)
        assertTrue(relay.summary()!!.contains("1 needed another try"))
    }

    @Test
    fun anAddressTheCensorBlocksIsLeftForTheNextOne() {
        val picky = FakeBackend { it == "203.0.113.2" }
        val relay = relay(listOf(Backend(picky.port, "p")), listOf("203.0.113.1", "203.0.113.2"))
        val socket = connect(relay)
        assertEquals("SH", socket.getInputStream().readText(2))
        assertTrue(picky.asked.containsAll(listOf("203.0.113.1", "203.0.113.2")))
    }

    @Test
    fun whatWorkedBeforeIsTriedFirst() {
        val silent = FakeBackend { false }
        val good = FakeBackend { true }
        val relay = relay(listOf(Backend(silent.port, "silent"), Backend(good.port, "good")))
        connect(relay).use { assertEquals("SH", it.getInputStream().readText(2)) }
        connect(relay).use { assertEquals("SH", it.getInputStream().readText(2)) }
        // The second connection asked the silent one nothing: the good one is first now.
        assertEquals(1, silent.asked.size)
        assertEquals(2, good.asked.size)
    }

    @Test
    fun aDeadBackendIsSkippedAtOnce() {
        val dead = ServerSocket(0).also { it.close() }.localPort
        val good = FakeBackend { true }
        val socket = connect(relay(listOf(Backend(dead, "dead"), Backend(good.port, "good"))))
        assertEquals("SH", socket.getInputStream().readText(2))
    }

    @Test
    fun whenNothingAnswersTheConnectionCloses() {
        val silent = FakeBackend { false }
        val relay = relay(listOf(Backend(silent.port, "silent")))
        val socket = connect(relay)
        assertEquals(-1, socket.getInputStream().read())
        assertNotNull(relay.summary())
    }

    @Test
    fun dohAnswerIsParsedAndIpv4GoesFirst() {
        val json = """{"Status":0,"Answer":[{"name":"x","type":5,"TTL":10,"data":"y."},
            {"name":"y","type":28,"TTL":120,"data":"2a00:1450::1"},{"name":"y","type":1,"TTL":300,"data":"142.250.1.1"}]}"""
        val (addresses, ttl) = Resolver.parse(json)!!
        assertEquals(120, ttl)
        assertEquals(2, addresses.size)
        assertEquals(listOf("142.250.1.1", "2a00:1450:0:0:0:0:0:1"), Resolver.ordered(addresses).map { it.hostAddress })
        assertNull(Resolver.parse("""{"Status":3}"""))
        assertNull(Resolver.parse("not json"))
    }

    @Test
    fun resolverFallsBackToThePhonesDnsAndKeepsTheAnswer() {
        var calls = 0
        val notes = mutableListOf<String>()
        val resolver = Resolver(
            client = { OkHttpClient() },
            endpoints = emptyList(),
            system = { calls++; listOf(InetAddress.getByName("203.0.113.9")) },
            note = { notes += it },
        )
        assertEquals("203.0.113.9", resolver.resolve("a.googlevideo.com").single().hostAddress)
        resolver.resolve("a.googlevideo.com")
        assertEquals(1, calls)
        assertFalse(resolver.resolve("198.51.100.1").isEmpty())
        assertEquals(1, notes.size)
    }
}
