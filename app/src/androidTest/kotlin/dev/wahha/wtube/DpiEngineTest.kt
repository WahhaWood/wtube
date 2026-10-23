package dev.wahha.wtube

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.dpi.DpiProbe1Service
import dev.wahha.wtube.dpi.DpiProbe2Service
import dev.wahha.wtube.dpi.DpiProbe3Service
import dev.wahha.wtube.dpi.DpiProcess
import dev.wahha.wtube.dpi.Probe
import dev.wahha.wtube.dpi.ProbeResult
import dev.wahha.wtube.dpi.SearchOutcome
import dev.wahha.wtube.dpi.Strategies
import dev.wahha.wtube.dpi.Strategy
import dev.wahha.wtube.dpi.StrategySearch
import dev.wahha.wtube.dpi.localSocks
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** byedpi in its own processes, over the real network (which needs no bypass, so YouTube must simply work through it). */
@RunWith(AndroidJUnit4::class)
class DpiEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val presets = context.assets.open("strategies.json").bufferedReader().use { it.readText() }
        .let { Strategies.parse(it, Int.MAX_VALUE)!!.strategies }

    private fun probeProcesses(): List<String> =
        context.getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().map { it.processName }.filter { ":dpiprobe" in it }

    @Test fun proxyCarriesYouTubeAndDiesWithItsBinding() = runBlocking {
        val engine = DpiProcess(context, DpiProbe1Service::class.java)
        val port = engine.start(Strategies.validate(presets.first().args)!!)
        assertTrue(probeProcesses().isNotEmpty())
        val result = Probe().check(localSocks(port))
        assertTrue(result.toString(), result.works)
        engine.stop()
        delay(500)
        assertEquals(emptyList<String>(), probeProcesses())
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
