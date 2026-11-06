package dev.wahha.wtube

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.dpi.Backend
import dev.wahha.wtube.dpi.DpiProbe1Service
import dev.wahha.wtube.dpi.DpiProbe2Service
import dev.wahha.wtube.dpi.DpiProbe3Service
import dev.wahha.wtube.dpi.DpiProcess
import dev.wahha.wtube.dpi.Probe
import dev.wahha.wtube.dpi.ProbeResult
import dev.wahha.wtube.dpi.Relay
import dev.wahha.wtube.dpi.Resolver
import dev.wahha.wtube.dpi.SearchOutcome
import dev.wahha.wtube.dpi.Strategies
import dev.wahha.wtube.dpi.Strategy
import dev.wahha.wtube.dpi.StrategySearch
import dev.wahha.wtube.dpi.localSocks
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress

/** byedpi in its own processes, over the real network (which needs no bypass, so YouTube must simply work through it). */
@RunWith(AndroidJUnit4::class)
class DpiEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val presets = context.assets.open("strategies.json").bufferedReader().use { it.readText() }
        .let { Strategies.parse(it, Int.MAX_VALUE)!!.strategies }

    private fun probeProcesses(): List<String> =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().map { it.processName }.filter { ":dpiprobe" in it }

    /** The first of a few presets that gets through: not all do on every network (bb01 does not on a plain home Wi-Fi). */
    private suspend fun workingEngine(check: suspend (port: Int) -> ProbeResult): Pair<DpiProcess, List<ProbeResult>> {
        val results = mutableListOf<ProbeResult>()
        for (preset in presets.take(4)) {
            val engine = DpiProcess(context, DpiProbe1Service::class.java)
            val port = engine.start(Strategies.validate(preset.args)!!)
            assertTrue(probeProcesses().isNotEmpty())
            results += check(port)
            if (results.last().works) return engine to results
            engine.stop()
            delay(500)
        }
        throw AssertionError("No preset got through: $results")
    }

    @Test fun proxyCarriesYouTubeAndDiesWithItsBinding() = runBlocking {
        val (engine) = workingEngine { port -> Probe().check(localSocks(port)) }
        engine.stop()
        delay(500)
        assertEquals(emptyList<String>(), probeProcesses())
    }

    // java.net's SOCKS client on Android has to log in to the relay by itself, for OkHttp and so for the whole app.
    @Test fun appRequestsLogInToTheRelay() = runBlocking {
        var backend = 0
        val resolver = Resolver(client = { OkHttpClient() }, endpoints = emptyList(), system = { InetAddress.getAllByName(it).toList() })
        val relay = Relay(resolver, { listOf(Backend(backend, "test")) })
        try {
            val (engine) = workingEngine { port ->
                backend = port
                Probe().check(relay.proxy)
            }
            engine.stop()
        } finally {
            relay.close()
        }
    }

    @Test fun searchRunsThreeAtATimeAndCleansUp() = runBlocking {
        val engines = listOf(DpiProbe1Service::class.java, DpiProbe2Service::class.java, DpiProbe3Service::class.java).map { DpiProcess(context, it) }
        val probe = Probe()
        val seen = mutableListOf<Pair<String, ProbeResult>>()
        // Pretend the network blocks YouTube, so the search has to go through the presets.
        val outcome = StrategySearch(engines, probe = { proxy -> if (proxy == null) ProbeResult.Failed else probe.check(proxy) })
            .run(presets.take(6), onResult = { s: Strategy, r -> seen += s.id to r })
        assertTrue("$outcome $seen", outcome is SearchOutcome.Found)
        assertTrue(seen.toString(), seen.size in 3..6)
        delay(500)
        assertEquals(emptyList<String>(), probeProcesses())
    }
}
