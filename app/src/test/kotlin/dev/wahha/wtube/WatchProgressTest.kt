package dev.wahha.wtube

import dev.wahha.wtube.data.AccountPosition
import dev.wahha.wtube.data.HistoryEntry
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressTest {
    private fun local(positionMs: Long, finished: Boolean = false, positionAt: Long = 1_000) = HistoryEntry(
        VideoItem("v", "t", null, "c", null, null, duration = 600, views = 1, uploaded = null, isLive = false),
        watchedAt = positionAt, positionMs = positionMs, finished = finished, positionAt = positionAt,
    )

    @Test
    fun accountWinsWithoutLocalPlace() {
        assertTrue(WatchProgress.accountWins(null, AccountPosition(5_000, false, 2_000)))
    }

    @Test
    fun localWinsWhenAccountIsNotFurther() {
        assertFalse(WatchProgress.accountWins(local(100_000), AccountPosition(100_000, false, 9_000)))
        assertFalse(WatchProgress.accountWins(local(100_000), AccountPosition(120_000, false, 9_000)))
        assertFalse(WatchProgress.accountWins(local(100_000), AccountPosition(30_000, false, 9_000)))
    }

    @Test
    fun accountWinsWhenFurtherThanLead() {
        assertTrue(WatchProgress.accountWins(local(100_000), AccountPosition(131_000, false, 9_000)))
    }

    @Test
    fun accountFinishedWinsOnlyOverOlderUnfinishedLocal() {
        assertTrue(WatchProgress.accountWins(local(100_000, positionAt = 1_000), AccountPosition(0, true, 2_000)))
        assertFalse(WatchProgress.accountWins(local(100_000, positionAt = 3_000), AccountPosition(0, true, 2_000)))
        assertFalse(WatchProgress.accountWins(local(100_000, finished = true), AccountPosition(0, true, 9_000)))
    }
}
