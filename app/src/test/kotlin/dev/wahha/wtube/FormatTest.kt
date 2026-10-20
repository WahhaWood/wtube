package dev.wahha.wtube

import dev.wahha.wtube.data.formatAgo
import dev.wahha.wtube.data.interleave
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.data.formatDuration
import dev.wahha.wtube.data.parseYoutubeTime
import dev.wahha.wtube.data.ruPlural
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    @Test
    fun duration() {
        assertEquals("", formatDuration(0))
        assertEquals("", formatDuration(-1))
        assertEquals("0:07", formatDuration(7))
        assertEquals("3:07", formatDuration(187))
        assertEquals("1:02:03", formatDuration(3723))
        assertEquals("10:00:00", formatDuration(36000))
    }

    @Test
    fun clockShowsZero() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:00", formatClock(999))
        assertEquals("1:05", formatClock(65_400))
    }

    @Test
    fun compactCountsTruncateLikeYoutube() {
        assertEquals("0", formatCompact(0))
        assertEquals("999", formatCompact(999))
        assertEquals("1 тыс.", formatCompact(1_000))
        assertEquals("1,2 тыс.", formatCompact(1_234))
        assertEquals("1,9 тыс.", formatCompact(1_999))
        assertEquals("12 тыс.", formatCompact(12_345))
        assertEquals("999 тыс.", formatCompact(999_999))
        assertEquals("1 млн", formatCompact(1_000_000))
        assertEquals("3,4 млн", formatCompact(3_456_789))
        assertEquals("1,1 млрд", formatCompact(1_100_000_000))
    }

    @Test
    fun russianPlurals() {
        assertEquals("1 год", ruPlural(1, "год", "года", "лет"))
        assertEquals("3 года", ruPlural(3, "год", "года", "лет"))
        assertEquals("5 лет", ruPlural(5, "год", "года", "лет"))
        assertEquals("11 лет", ruPlural(11, "год", "года", "лет"))
        assertEquals("12 лет", ruPlural(12, "год", "года", "лет"))
        assertEquals("21 год", ruPlural(21, "год", "года", "лет"))
        assertEquals("22 года", ruPlural(22, "год", "года", "лет"))
        assertEquals("111 лет", ruPlural(111, "год", "года", "лет"))
    }

    @Test
    fun ago() {
        val now = 1_800_000_000_000L
        val minute = 60_000L
        val day = 24 * 60 * minute
        assertEquals("только что", formatAgo(now - 30_000, now))
        assertEquals("только что", formatAgo(now + day, now))
        assertEquals("1 минуту назад", formatAgo(now - minute, now))
        assertEquals("5 минут назад", formatAgo(now - 5 * minute, now))
        assertEquals("2 часа назад", formatAgo(now - 120 * minute, now))
        assertEquals("1 день назад", formatAgo(now - day, now))
        assertEquals("1 неделю назад", formatAgo(now - 8 * day, now))
        assertEquals("3 недели назад", formatAgo(now - 22 * day, now))
        assertEquals("3 месяца назад", formatAgo(now - 95 * day, now))
        assertEquals("21 год назад", formatAgo(now - 21 * 366 * day, now))
    }

    @Test
    fun youtubeTimeParameter() {
        assertEquals(95L, parseYoutubeTime("95"))
        assertEquals(95L, parseYoutubeTime("95s"))
        assertEquals(95L, parseYoutubeTime("1m35s"))
        assertEquals(3723L, parseYoutubeTime("1h2m3s"))
        assertEquals(7200L, parseYoutubeTime("2h"))
        assertNull(parseYoutubeTime(""))
        assertNull(parseYoutubeTime("abc"))
    }

    @Test
    fun interleaveTakesRoundRobin() {
        assertEquals(listOf(1, 10, 100, 2, 20, 3), interleave(listOf(listOf(1, 2, 3), listOf(10, 20), listOf(100))))
        assertEquals(emptyList<Int>(), interleave(emptyList<List<Int>>()))
    }
}
