package dev.wahha.wtube

import dev.wahha.wtube.dpi.FirstRoute
import dev.wahha.wtube.dpi.Restarts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class DpiPolicyTest {
    @Test
    fun restartsUntilThreeDeathsInAMinute() {
        val restarts = Restarts()
        assertTrue(restarts.restartAfterDeath(0))
        assertTrue(restarts.restartAfterDeath(10_000))
        assertFalse(restarts.restartAfterDeath(20_000))
    }

    @Test
    fun deathsSpreadOverMinutesKeepRestarting() {
        val restarts = Restarts()
        for (minute in 0 until 10) assertTrue(restarts.restartAfterDeath(minute * 61_000L))
    }

    @Test
    fun oldDeathsLeaveTheWindow() {
        val restarts = Restarts()
        restarts.restartAfterDeath(0)
        restarts.restartAfterDeath(30_000)
        // The first one is more than a minute old by now: two in the window.
        assertTrue(restarts.restartAfterDeath(61_000))
        assertFalse(restarts.restartAfterDeath(62_000))
    }

    @Test
    fun clearedCountStartsAfresh() {
        val restarts = Restarts()
        repeat(3) { restarts.restartAfterDeath(it * 1_000L) }
        restarts.clear()
        assertTrue(restarts.restartAfterDeath(4_000))
        assertTrue(restarts.restartAfterDeath(5_000))
    }

    @Test
    fun firstRouteWaitsOnceThenNoMore() {
        val route = FirstRoute(waitMs = 300)
        val first = elapsed { route.await() }
        assertTrue("first wait $first ms", first >= 250)
        assertTrue("second wait", elapsed { route.await() } < 100)
    }

    @Test
    fun firstRouteReleasesWaitersWhenItArrives() {
        val route = FirstRoute(waitMs = 5_000)
        thread { Thread.sleep(100); route.arrived() }
        assertTrue(elapsed { route.await() } < 2_000)
        assertTrue(elapsed { route.await() } < 100)
    }

    private fun elapsed(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }
}
