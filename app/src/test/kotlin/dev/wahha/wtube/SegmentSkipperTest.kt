package dev.wahha.wtube

import dev.wahha.wtube.data.Segment
import dev.wahha.wtube.player.segmentToSkip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentSkipperTest {
    private val sponsor = Segment(startMs = 10_000, endMs = 20_000, category = "sponsor", mute = false)
    private val muted = Segment(startMs = 30_000, endMs = 40_000, category = "music_offtopic", mute = true)
    private val segments = listOf(sponsor, muted)

    @Test
    fun playbackRunningIntoASegmentSkipsIt() {
        assertEquals(sponsor, segmentToSkip(segments, position = 10_100, previous = 9_850))
        // The start itself is inside; the end is not.
        assertEquals(sponsor, segmentToSkip(segments, position = 10_000, previous = 9_750))
        assertNull(segmentToSkip(segments, position = 20_000, previous = 19_750))
    }

    @Test
    fun firstTickOfAVideoSkipsASegmentAtItsStart() {
        val intro = Segment(0, 5_000, "intro", mute = false)
        assertEquals(intro, segmentToSkip(listOf(intro), position = 200, previous = -1))
    }

    @Test
    fun seekingDeepIntoASegmentPlaysIt() {
        assertNull(segmentToSkip(segments, position = 15_000, previous = 3_000))
    }

    @Test
    fun alreadyInsideIsNotSkippedAgain() {
        // Sought back to the segment's start and watching: the previous tick was inside too.
        assertNull(segmentToSkip(segments, position = 10_500, previous = 10_250))
    }

    @Test
    fun mutedSegmentsPlay() {
        assertNull(segmentToSkip(segments, position = 30_100, previous = 29_850))
    }

    @Test
    fun outsideAnySegmentNothing() {
        assertNull(segmentToSkip(segments, position = 25_000, previous = 24_750))
        assertNull(segmentToSkip(emptyList(), position = 10_100, previous = 9_850))
    }
}
