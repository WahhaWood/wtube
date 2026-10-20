package dev.wahha.wtube

import dev.wahha.wtube.player.videoplaybackUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class YoutubeRequestsTest {
    private val base = "https://rr1.googlevideo.com/videoplayback?itag=251&id=x"

    @Test
    fun wholeResourceHasNoRange() {
        assertEquals("$base&rn=0", videoplaybackUrl(base, 0, -1, 0))
    }

    @Test
    fun boundedRangeIsInclusive() {
        assertEquals("$base&rn=3&range=100-1123", videoplaybackUrl(base, 100, 1024, 3))
    }

    @Test
    fun openEndedRangeFromPosition() {
        assertEquals("$base&rn=1&range=5000-", videoplaybackUrl(base, 5000, -1, 1))
    }

    @Test
    fun keepsExistingRequestNumber() {
        val url = "$base&rn=7"
        assertEquals("$url&range=0-99", videoplaybackUrl(url, 0, 100, 42))
    }
}
