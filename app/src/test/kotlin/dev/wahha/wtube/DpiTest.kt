package dev.wahha.wtube

import dev.wahha.wtube.dpi.Probe
import dev.wahha.wtube.dpi.ProbeResult
import dev.wahha.wtube.dpi.ProxyEngine
import dev.wahha.wtube.dpi.SearchOutcome
import dev.wahha.wtube.dpi.Strategies
import dev.wahha.wtube.dpi.Strategy
import dev.wahha.wtube.dpi.StrategySearch
import dev.wahha.wtube.dpi.isBypassHost
import dev.wahha.wtube.dpi.networkKey
import dev.wahha.wtube.dpi.parseProxy
import dev.wahha.wtube.dpi.route
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class DpiTest {
    @Test
    fun bundledPresetsAllPass() {
        val text = File("../dpi/strategies.json").readText()
        val list = Strategies.parse(text, appVersion = Int.MAX_VALUE)
        assertNotNull(list)
        assertEquals(60, list!!.strategies.size)
        assertEquals(list.strategies.size, list.strategies.map { it.id }.toSet().size)
    }

    @Test
    fun validateAcceptsStrategyFlags() {
        assertEquals(
            listOf("-n", Strategies.FAKE_SNI, "-Qr", "-f-204", "-s1:5+sm", "-a1", "-As"),
            Strategies.validate("-n {sni} -Qr -f-204 -s1:5+sm -a1 -As"),
        )
        assertNotNull(Strategies.validate("--split 1 --disorder=3+s --tlsrec 1+s -SN"))
        assertNotNull(Strategies.validate("-H:youtube.com -l ':\\x16\\x03'"))
        assertEquals(listOf("-n", "a b"), Strategies.validate("-n \"a b\""))
    }

    @Test
    fun validateRefusesWhatIsNotAStrategy() {
        for (bad in listOf(
            "-i 0.0.0.0", "-p 1080", "--port=1080", "-s1 -x2", "-D", "-y /sdcard/cache", "-H /etc/hosts", "--hosts=/x",
            "-l /data/fake.bin", "-C 1.2.1.1", "-P /x", "-s", "--split", "plain", "-s1 \"unclosed", "", "--tfo=1", "-Si0.0.0.0",
        )) {
            assertNull(bad, Strategies.validate(bad))
        }
    }

    @Test
    fun parseDropsBadEntriesAndRespectsMinApp() {
        val text = """{"version":3,"minApp":10,"strategies":[{"id":"a","args":"-s1"},{"id":"b","args":"-p 80"},{"id":"a","args":"-d1"},{"id":"","args":"-s2"}]}"""
        val list = Strategies.parse(text, appVersion = 10)!!
        assertEquals(listOf(Strategy("a", "-s1")), list.strategies)
        assertNull(Strategies.parse(text, appVersion = 9))
        assertNull(Strategies.parse("not json", appVersion = 10))
        assertNull(Strategies.parse("""{"version":1,"strategies":[{"id":"x","args":"-i 1"}]}""", appVersion = 10))
    }

    @Test
    fun onlyYouTubeHostsGoThroughTheBypass() {
        for (host in listOf("www.youtube.com", "youtube.com", "rr3---sn-abc.googlevideo.com", "i.ytimg.com", "yt3.ggpht.com", "youtubei.googleapis.com", "YOUTU.BE")) {
            assertTrue(host, isBypassHost(host))
        }
        for (host in listOf("sponsor.ajay.app", "returnyoutubedislikeapi.com", "api.github.com", "raw.githubusercontent.com", "notyoutube.com", "youtube.com.evil.org")) {
            assertFalse(host, isBypassHost(host))
        }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 1080))
        // Never direct as a second choice: OkHttp would race it against the proxy.
        assertEquals(listOf(proxy), route("rr1.googlevideo.com", proxy))
        assertEquals(listOf(Proxy.NO_PROXY), route("api.github.com", proxy))
        assertEquals(listOf(Proxy.NO_PROXY), route("www.youtube.com", null))
    }

    @Test
    fun customProxyParses() {
        assertEquals(Proxy.Type.SOCKS, parseProxy("10.0.0.2:1080")!!.type())
        assertEquals(Proxy.Type.SOCKS, parseProxy("socks5://proxy.example:9050")!!.type())
        val http = parseProxy(" http://[::1]:3128/ ")!!
        assertEquals(Proxy.Type.HTTP, http.type())
        assertEquals(3128, (http.address() as InetSocketAddress).port)
        for (bad in listOf("", "host", "host:0", "host:70000", "ftp://h:1", "a b:1", ":80")) assertNull(bad, parseProxy(bad))
    }

    @Test
    fun networkKeyIsStableAndAnonymous() {
        assertEquals("cell:25001", networkKey(true, "25001", listOf("10.0.0.1"), listOf("8.8.8.8")))
        assertEquals("cell:unknown", networkKey(true, "", emptyList(), emptyList()))
        val home = networkKey(false, null, listOf("192.168.1.1"), listOf("192.168.1.1", "1.1.1.1"))
        assertEquals(home, networkKey(false, null, listOf("192.168.1.1"), listOf("1.1.1.1", "192.168.1.1")))
        assertTrue(home.startsWith("net:") && "192" !in home)
        assertTrue(home != networkKey(false, null, listOf("192.168.0.1"), listOf("192.168.1.1", "1.1.1.1")))
    }

    // ---- Search over fake engines ----

    private class FakeEngine(private val failOn: Set<String> = emptySet()) : ProxyEngine {
        var running: List<String>? = null
        var starts = 0
        var stops = 0

        override suspend fun start(args: List<String>): Int {
            starts++
            if (args.joinToString(" ") in failOn) throw IOException("bind failed")
            running = args
            return 1000 + args.hashCode().and(0xfff)
        }

        override suspend fun stop() {
            stops++
            running = null
        }
    }

    private val fast = ProbeResult(site = true, images = true, video = true, kbps = 5_000)
    private val slow = ProbeResult(site = true, images = true, video = true, kbps = 500)
    private val blocked = ProbeResult.Failed
    private fun strategies(n: Int) = (1..n).map { Strategy("s$it", "-s$it") }

    /** [results] by the port's strategy; direct is "direct". */
    private fun search(engines: List<FakeEngine>, results: Map<String, ProbeResult>): StrategySearch {
        val byPort = mutableMapOf<Int, String>()
        return StrategySearch(
            engines,
            probe = { proxy ->
                if (proxy == null) results.getValue("direct")
                else results[byPort.getValue((proxy.address() as InetSocketAddress).port)] ?: blocked
            },
            proxyOf = { port ->
                byPort[port] = engines.first { 1000 + (it.running?.hashCode() ?: 0).and(0xfff) == port }.running!!.single().removePrefix("-")
                Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("localhost", port))
            },
        )
    }

    @Test
    fun fastDirectNeedsNoBypass() = runBlocking {
        val engine = FakeEngine()
        assertEquals(SearchOutcome.Direct, search(listOf(engine), mapOf("direct" to fast)).run(strategies(5)))
        assertEquals(0, engine.starts)
    }

    @Test
    fun earliestFastStrategyInTheFirstGoodBatchWins() = runBlocking {
        val engines = List(3) { FakeEngine() }
        val seen = mutableListOf<String>()
        val outcome = search(engines, mapOf("direct" to blocked, "s5" to fast, "s6" to fast, "s9" to fast))
            .run(strategies(10), onResult = { s, _ -> seen += s.id })
        assertEquals("s5", (outcome as SearchOutcome.Found).strategy.id)
        assertTrue(outcome.fast)
        assertEquals((1..6).map { "s$it" }, seen)
        engines.forEach { assertEquals(it.starts, it.stops); assertNull(it.running) }
    }

    @Test
    fun withoutFastTheQuickestWorkingOneIsTaken() = runBlocking {
        val outcome = search(List(2) { FakeEngine() }, mapOf("direct" to blocked, "s2" to slow, "s3" to slow.copy(kbps = 900)))
            .run(strategies(4))
        assertEquals("s3", (outcome as SearchOutcome.Found).strategy.id)
        assertFalse(outcome.fast)
    }

    @Test
    fun slowDirectBeatsAnEvenSlowerStrategy() = runBlocking {
        val outcome = search(listOf(FakeEngine()), mapOf("direct" to slow, "s1" to slow.copy(kbps = 100))).run(strategies(2))
        assertEquals(SearchOutcome.Direct, outcome)
    }

    @Test
    fun nothingWorksAndBrokenEnginesCountAsFailed() = runBlocking {
        val engine = FakeEngine(failOn = setOf("-s2"))
        val results = mutableMapOf<String, ProbeResult>()
        val outcome = search(listOf(engine), mapOf("direct" to blocked)).run(strategies(3), onResult = { s, r -> results[s.id] = r })
        assertEquals(SearchOutcome.None, outcome)
        assertEquals(blocked, results["s2"])
        assertEquals(3, engine.stops)
    }

    // ---- Probe against a local server ----

    private fun server(videoBody: MockResponse): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path!!.substringBefore('?')) {
                "/generate_204" -> MockResponse().setResponseCode(204)
                "/thumb.jpg" -> MockResponse().setBody("jpeg")
                "/videoplayback" -> videoBody
                else -> MockResponse().setResponseCode(404)
            }
        }
        start()
    }

    private fun probe(server: MockWebServer, streamUrl: String?) = Probe(
        siteUrl = server.url("/generate_204").toString(),
        imageUrl = server.url("/thumb.jpg").toString(),
        streamUrl = { streamUrl },
    )

    @Test
    fun probeReadsAMegabyteOfVideo() = runBlocking {
        val server = server(MockResponse().setBody(Buffer().write(ByteArray(Probe.VIDEO_BYTES.toInt()))))
        try {
            val result = probe(server, server.url("/videoplayback?id=1").toString()).check(null)
            assertTrue(result.toString(), result.works && result.fast)
            assertTrue(server.takeRequest().path == "/generate_204")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun throttledVideoIsNotWorking() = runBlocking {
        // 64 KB in 8 s: the censor's trickle.
        val trickle = MockResponse().setBody(Buffer().write(ByteArray(Probe.VIDEO_BYTES.toInt()))).throttleBody(8 * 1024, 1, TimeUnit.SECONDS)
        val server = server(trickle)
        try {
            val result = probe(server, server.url("/videoplayback?id=1").toString()).check(null)
            assertTrue(result.site && result.images)
            assertFalse(result.toString(), result.video)
            assertTrue(result.note, " KB in " in result.note)
        } finally {
            server.shutdown()
        }
    }

    /** 0.20.0 counted a bare googlevideo answer when no stream link came, and picked a route that stalled video. */
    @Test
    fun withoutAStreamLinkTheRouteDoesNotWork() = runBlocking {
        val server = server(MockResponse().setResponseCode(500))
        try {
            val result = probe(server, streamUrl = null).check(null)
            assertTrue(result.site && result.images)
            assertFalse(result.works)
            assertEquals("no stream link", result.note)
        } finally {
            server.shutdown()
        }
    }
}
