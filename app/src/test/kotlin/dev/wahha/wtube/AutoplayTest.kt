package dev.wahha.wtube

import dev.wahha.wtube.player.Sleep
import dev.wahha.wtube.player.mayPlayUpcoming
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer against the next video of the end screen's countdown (or of the queue). */
class AutoplayTest {
    @Test
    fun noTimerPlays() {
        assertTrue(mayPlayUpcoming(1_000, null))
    }

    @Test
    fun timerDueDuringTheCountdownStopsIt() {
        assertFalse(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 5_000, endOfVideo = false)))
        // Woken together after a doze: same moment, the timer wins.
        assertFalse(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 8_000, endOfVideo = false)))
    }

    @Test
    fun timerDueLaterLetsItPlay() {
        assertTrue(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 8_001, endOfVideo = false)))
    }

    // "End of video" stops before any next video is scheduled; a "next" pressed by hand still plays.
    @Test
    fun endOfVideoTimerDoesNotBlockNext() {
        assertTrue(mayPlayUpcoming(8_000, Sleep(atMs = null, endOfVideo = true)))
    }
}
