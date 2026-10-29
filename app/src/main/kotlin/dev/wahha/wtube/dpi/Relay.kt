package dev.wahha.wtube.dpi

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A byedpi proxy the [Relay] can send a connection through; [label] is the strategy id, for the log. */
data class Backend(val port: Int, val label: String)

/**
 * A SOCKS5 proxy in the app's process that stands in front of the byedpi proxies. A desync strategy works
 * on one connection and not on the next, and on one server address and not on another (0.20.4 log: a
 * strategy gave the first megabyte and the next connection stayed silent), so the relay makes the
 * connection reliable instead of hoping for the one best strategy:
 *  - it resolves names itself ([Resolver]) and hands byedpi an address;
 *  - it answers the client at once, keeps the client's first packet (the TLS ClientHello) and sends it
 *    through a (strategy, address) pair; if the server says nothing within [hedgeMs] it starts another pair
 *    next to the first (at once if the first failed), and the first one that gets a byte back wins;
 *  - the pairs that won before are tried first.
 * After the first byte it only copies bytes, so TLS stays between the client and the server.
 */
class Relay(
    private val resolver: Resolver,
    private val backends: () -> List<Backend>,
    private val note: (String) -> Unit = {},
    private val hedgeMs: Long = HEDGE_MS,
    private val attemptMs: Long = ATTEMPT_MS,
    private val deadlineMs: Long = DEADLINE_MS,
    private val maxAttempts: Int = MAX_ATTEMPTS,
) : Closeable {
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val closed = AtomicBoolean()
    private val quality = ConcurrentHashMap<String, IntArray>()
    private val noted = ConcurrentHashMap.newKeySet<String>()
    private val counters = ConcurrentHashMap<String, IntArray>()
    private val firstByteMs = ArrayList<Long>()

    val port: Int get() = server.localPort
    val proxy get() = localSocks(port)

    init {
        pool.execute {
            while (!closed.get()) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { handle(client) }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }

    /** "bb28 12 ok 3 failed, bb24 1 ok 0 failed; 14 connections, 3 needed another try, 1 got nothing; first byte 340 ms"; null if idle. */
    fun summary(reset: Boolean = true): String? {
        val races = counter("races")
        if (races == 0) return null
        val perBackend = counters.entries.filter { it.key.startsWith("b:") }.sortedBy { it.key }
            .joinToString(", ") { "${it.key.removePrefix("b:")} ${it.value[0]} ok ${it.value[1]} failed" }
        val median = synchronized(firstByteMs) { firstByteMs.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] } }
        val text = "$perBackend; $races connections, ${counter("retried")} needed another try, ${counter("nothing")} got nothing; " +
            "first byte ${median} ms"
        if (reset) {
            counters.clear()
            synchronized(firstByteMs) { firstByteMs.clear() }
        }
        return text
    }

    private fun counter(name: String) = counters[name]?.get(0) ?: 0

    private fun bump(name: String, slot: Int = 0) {
        counters.computeIfAbsent(name) { IntArray(2) }.also { synchronized(it) { it[slot]++ } }
    }

    // ---- One client connection ----

    private fun handle(client: Socket) {
        open += client
        try {
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()
            if (input.read() != 5) return
            repeat(input.read()) { input.read() }
            output.write(byteArrayOf(5, 0))
            val head = ByteArray(4)
            readFully(input, head)
            if (head[1].toInt() != 1) {
                output.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                return
            }
            val address: ByteArray
            var host: String? = null
            when (head[3].toInt()) {
                1 -> address = ByteArray(4).also { readFully(input, it) }
                4 -> address = ByteArray(16).also { readFully(input, it) }
                3 -> {
                    val name = ByteArray(input.read())
                    readFully(input, name)
                    host = String(name, Charsets.US_ASCII)
                    address = name
                }
                else -> return
            }
            val portBytes = ByteArray(2).also { readFully(input, it) }
            val targetPort = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)
            output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            output.flush()

            val addresses = if (host == null) listOf(InetAddress.getByAddress(address)) else resolver.resolve(host)
            if (addresses.isEmpty()) {
                note("relay: cannot resolve $host")
                return
            }
            val preface = readPreface(client, input)
            val name = host ?: addresses.first().hostAddress.orEmpty()
            val winner = race(name, targetPort, addresses, preface) ?: return
            val upstream = winner.socket ?: return
            open += upstream
            try {
                output.write(winner.first, 0, winner.length)
                output.flush()
                client.soTimeout = 0
                pipe(client, upstream)
            } finally {
                runCatching { upstream.close() }
                open -= upstream
            }
        } catch (_: IOException) {
        } finally {
            runCatching { client.close() }
            open -= client
        }
    }

    /** What the client sends first (a TLS ClientHello), as it comes; empty if it waits for the server to speak. */
    private fun readPreface(client: Socket, input: InputStream): ByteArray {
        client.soTimeout = PREFACE_WAIT_MS
        val buffer = ByteArray(PREFACE_MAX)
        val n = try {
            input.read(buffer)
        } catch (_: SocketTimeoutException) {
            0
        }
        if (n <= 0) return ByteArray(0)
        var total = n
        // A hello can arrive in two writes.
        Thread.sleep(15)
        while (total < buffer.size && input.available() > 0) {
            val more = input.read(buffer, total, buffer.size - total)
            if (more <= 0) break
            total += more
        }
        return buffer.copyOf(total)
    }

    private fun pipe(a: Socket, b: Socket) {
        val back = pool.submit { copy(b, a) }
        copy(a, b)
        runCatching { a.close() }
        runCatching { b.close() }
        runCatching { back.get(1, TimeUnit.SECONDS) }
    }

    private fun copy(from: Socket, to: Socket) {
        val buffer = ByteArray(COPY_BUFFER)
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
            }
        } catch (_: IOException) {
        }
        runCatching { to.shutdownOutput() }
    }

    // ---- Attempts ----

    private class Combo(val backend: Backend, val address: InetAddress) {
        val key get() = "${backend.label}|${address.hostAddress}"
    }

    private class Attempt(val combo: Combo, val index: Int) {
        val cancelled = AtomicBoolean()
        @Volatile var socket: Socket? = null
        @Volatile var first: ByteArray = ByteArray(0)
        @Volatile var length = 0
        @Volatile var ms = 0L
        @Volatile var error: String? = null

        fun cancel() {
            cancelled.set(true)
            runCatching { socket?.close() }
        }
    }

    private fun combos(addresses: List<InetAddress>): List<Combo> {
        val list = backends()
        if (list.isEmpty()) return emptyList()
        val shown = addresses.take(MAX_ADDRESSES)
        val diagonal = ArrayList<Combo>()
        for (d in shown.indices) for ((b, backend) in list.withIndex()) diagonal += Combo(backend, shown[(b + d) % shown.size])
        val all = diagonal.distinctBy { it.key }
        return all.sortedByDescending { score(it.key) }
    }

    private fun score(key: String): Double {
        val q = quality[key] ?: return 0.5
        synchronized(q) { return (q[0] + 1.0) / (q[0] + q[1] + 2.0) }
    }

    private fun record(key: String, ok: Boolean) {
        val q = quality.computeIfAbsent(key) { IntArray(2) }
        synchronized(q) {
            q[if (ok) 0 else 1]++
            if (q[0] + q[1] > QUALITY_WINDOW) {
                q[0] /= 2
                q[1] /= 2
            }
        }
    }

    private fun race(host: String, targetPort: Int, addresses: List<InetAddress>, preface: ByteArray): Attempt? {
        val order = combos(addresses)
        if (order.isEmpty()) return null
        bump("races")
        val results = LinkedBlockingQueue<Attempt>()
        val running = ArrayList<Attempt>()
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        fun launch() {
            val attempt = Attempt(order[running.size], running.size)
            running += attempt
            pool.execute {
                tryOnce(attempt, targetPort, preface, results)
            }
        }
        var finished = 0
        var nextStart = hedgeMs
        var winner: Attempt? = null
        val limit = minOf(maxAttempts, order.size)
        launch()
        while (winner == null) {
            val now = elapsed()
            if (now >= deadlineMs) break
            val wait = if (running.size < limit) minOf(nextStart - now, deadlineMs - now) else deadlineMs - now
            val result = results.poll(wait.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            if (result != null) {
                finished++
                if (result.error == null) {
                    winner = result
                } else {
                    record(result.combo.key, false)
                    bump("b:" + result.combo.backend.label, 1)
                    noteFailure(host, result)
                    if (running.size < limit) {
                        launch()
                        nextStart = elapsed() + hedgeMs
                    } else if (finished == running.size) {
                        break
                    }
                }
            } else if (running.size < limit && elapsed() >= nextStart) {
                launch()
                nextStart = elapsed() + hedgeMs
            }
        }
        running.filter { it !== winner }.forEach { it.cancel() }
        if (winner == null) {
            bump("nothing")
            val line = "relay: ${bypassDomain(host) ?: host} got nothing from ${running.size} tries"
            if (noted.size < MAX_NOTES && noted.add(line)) note("$line in ${elapsed()} ms")
            return null
        }
        record(winner.combo.key, true)
        bump("b:" + winner.combo.backend.label, 0)
        if (winner.index > 0) bump("retried")
        synchronized(firstByteMs) { if (firstByteMs.size < 500) firstByteMs += winner.ms }
        return winner
    }

    private fun noteFailure(host: String, attempt: Attempt) {
        val domain = bypassDomain(host) ?: host
        val line = "relay: $domain ${attempt.error} via ${attempt.combo.backend.label} @ ${attempt.combo.address.hostAddress}"
        if (noted.size < MAX_NOTES && noted.add(line)) note(line)
    }

    /** One try: connect to the backend, ask it for [Combo.address], send the hello, wait for the first byte. */
    private fun tryOnce(attempt: Attempt, targetPort: Int, preface: ByteArray, results: LinkedBlockingQueue<Attempt>) {
        val started = System.nanoTime()
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            attempt.socket = socket
            if (attempt.cancelled.get()) return
            socket.connect(InetSocketAddress("127.0.0.1", attempt.combo.backend.port), CONNECT_MS)
            socket.soTimeout = BACKEND_REPLY_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            output.write(byteArrayOf(5, 1, 0))
            val greeting = ByteArray(2).also { readFully(input, it) }
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) throw IOException("bad greeting")
            val address = attempt.combo.address
            val request = java.io.ByteArrayOutputStream()
            request.write(byteArrayOf(5, 1, 0, if (address is Inet4Address) 1 else 4))
            request.write(address.address)
            request.write(byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte()))
            output.write(request.toByteArray())
            val reply = ByteArray(4).also { readFully(input, it) }
            if (reply[1].toInt() != 0) throw IOException("refused ${reply[1]}")
            readFully(input, ByteArray(if (reply[3].toInt() == 4) 18 else 6))
            if (preface.isNotEmpty()) output.write(preface)
            output.flush()
            socket.soTimeout = attemptMs.toInt()
            val buffer = ByteArray(PREFACE_MAX)
            val n = input.read(buffer)
            if (n < 0) throw IOException("closed")
            if (attempt.cancelled.get()) {
                socket.close()
                return
            }
            attempt.first = buffer
            attempt.length = n
            attempt.ms = (System.nanoTime() - started) / 1_000_000
            results.add(attempt)
            return
        } catch (e: SocketTimeoutException) {
            attempt.error = "silent for ${(System.nanoTime() - started) / 1_000_000} ms"
        } catch (e: IOException) {
            attempt.error = e.message?.take(40) ?: e.javaClass.simpleName
        }
        runCatching { socket.close() }
        if (!attempt.cancelled.get()) results.add(attempt)
    }

    companion object {
        /** A second pair starts next to the first when the server has not answered in this long. */
        const val HEDGE_MS = 1_500L
        /** A pair that has not got a byte back this long after the hello is given up on. */
        const val ATTEMPT_MS = 4_000L
        /** The whole connection: the player's own read timeout for googlevideo is 15 s. */
        const val DEADLINE_MS = 12_000L
        const val MAX_ATTEMPTS = 4
        private const val MAX_ADDRESSES = 4
        private const val CONNECT_MS = 2_000
        private const val BACKEND_REPLY_MS = 5_000
        private const val HANDSHAKE_MS = 10_000
        private const val PREFACE_WAIT_MS = 3_000
        private const val PREFACE_MAX = 16 * 1024
        private const val COPY_BUFFER = 32 * 1024
        private const val QUALITY_WINDOW = 40
        private const val MAX_NOTES = 60

        private fun readFully(input: InputStream, into: ByteArray) {
            var done = 0
            while (done < into.size) {
                val n = input.read(into, done, into.size - done)
                if (n < 0) throw IOException("eof")
                done += n
            }
        }
    }
}
