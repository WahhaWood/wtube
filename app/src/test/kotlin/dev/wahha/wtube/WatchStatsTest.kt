package dev.wahha.wtube

import dev.wahha.wtube.data.Backup
import dev.wahha.wtube.data.StatsEntry
import dev.wahha.wtube.data.StatsRange
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.formatWatchTime
import dev.wahha.wtube.data.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class WatchStatsTest {
    private val today = 20_000L

    private fun entry(day: Long, id: String, seconds: Long, channel: String? = "C$id", url: String? = "u$id") =
        StatsEntry(day, id, "T$id", channel, url, seconds)

    @Test
    fun sameVideoOnSameDaySumsUp() {
        val merged = WatchStats.merge(listOf(entry(today, "a", 60)), listOf(entry(today, "a", 30), entry(today - 1, "a", 5)))
        assertEquals(2, merged.size)
        assertEquals(90, merged.first { it.day == today }.seconds)
    }

    @Test
    fun oldDaysAreDropped() {
        val merged = WatchStats.merge(listOf(entry(today - 500, "a", 60)), listOf(entry(today, "b", 1)))
        assertEquals(listOf("b"), merged.map { it.videoId })
    }

    @Test
    fun weekCountsOnlyTheLastSevenDays() {
        val entries = listOf(entry(today, "a", 600), entry(today - 6, "b", 300), entry(today - 7, "c", 9_000))
        val week = summarize(entries, today, StatsRange.WEEK)
        assertEquals(900, week.totalSeconds)
        assertEquals(2, week.videos)
        assertEquals(listOf(300L, 0, 0, 0, 0, 0, 600), week.chart)
        assertEquals(9_900, summarize(entries, today, StatsRange.ALL).totalSeconds)
    }

    @Test
    fun allTimeChartIsWeeklyAndCoversTwelveWeeks() {
        val entries = listOf(entry(today, "a", 10), entry(today - 6, "a", 20), entry(today - 7, "a", 40), entry(today - 84, "a", 99))
        val chart = summarize(entries, today, StatsRange.ALL).chart
        assertEquals(12, chart.size)
        assertEquals(30, chart.last())
        assertEquals(40, chart[10])
    }

    @Test
    fun channelsAreRankedAcrossVideos() {
        val entries = listOf(
            entry(today, "a", 100, "One", "u1"),
            entry(today, "b", 150, "One", "u1"),
            entry(today, "c", 200, "Two", "u2"),
        )
        val summary = summarize(entries, today, StatsRange.WEEK)
        assertEquals(listOf("One", "Two"), summary.channels.map { it.name })
        assertEquals(250, summary.channels.first().seconds)
        assertEquals(listOf("c", "b", "a"), summary.topVideos.map { it.id })
    }

    @Test
    fun streakEndsAtYesterdayIfTodayIsEmpty() {
        val entries = listOf(entry(today - 1, "a", 1), entry(today - 2, "a", 1), entry(today - 4, "a", 1))
        assertEquals(2, summarize(entries, today, StatsRange.WEEK).streak)
        assertEquals(3, summarize(entries + entry(today, "b", 1), today, StatsRange.WEEK).streak)
        assertEquals(0, summarize(listOf(entry(today - 3, "a", 1)), today, StatsRange.WEEK).streak)
    }

    @Test
    fun dayFollowsTheLocalCalendar() {
        val zone = ZoneId.of("Asia/Tokyo")
        // 2026-10-22 16:00 UTC is already the 23rd in Tokyo.
        val millis = java.time.Instant.parse("2026-10-22T16:00:00Z").toEpochMilli()
        assertEquals(java.time.LocalDate.of(2026, 10, 23).toEpochDay(), WatchStats.dayOf(millis, zone))
    }

    @Test
    fun watchTimeReadsInHoursAndMinutes() {
        assertEquals("< 1 мин", formatWatchTime(59))
        assertEquals("5 мин", formatWatchTime(300))
        assertEquals("2 ч 05 мин", formatWatchTime(7_500))
    }

    @Test
    fun backupCarriesStats() {
        val backup = Backup.parse("""{"settings":{},"history":[],"stats":[{"day":5,"videoId":"v","title":"T","seconds":42}]}""")
        assertEquals(42L, backup.stats.single().seconds)
        assertTrue(Backup.parse(Backup.current()).stats.isEmpty())
    }
}
