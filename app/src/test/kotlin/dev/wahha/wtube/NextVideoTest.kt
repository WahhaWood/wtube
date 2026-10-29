package dev.wahha.wtube

import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.player.NextVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextVideoTest {
    private fun video(id: String, live: Boolean = false) =
        VideoItem(id, "t", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = live)

    @Test
    fun candidatesSkipLiveCurrentAndHidden() {
        val hidden = HiddenItems(videos = listOf(video("h")))
        val related = listOf(video("cur"), video("live", live = true), video("h"), video("ok"))
        assertEquals(listOf("ok"), NextVideo.candidates(related, "cur", hidden).map { it.id })
    }

    @Test
    fun picksUnwatchedFirstThenFallsBackOnlyWhenAsked() {
        val c = listOf(video("a"), video("b"))
        assertEquals("b", NextVideo.pick(c, setOf("a"), orWatched = false)?.id)
        assertNull(NextVideo.pick(c, setOf("a", "b"), orWatched = false))
        assertEquals("a", NextVideo.pick(c, setOf("a", "b"), orWatched = true)?.id)
        assertNull(NextVideo.pick(emptyList(), emptySet(), orWatched = true))
    }
}
