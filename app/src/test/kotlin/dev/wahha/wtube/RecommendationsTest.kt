package dev.wahha.wtube

import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.Recommendations
import dev.wahha.wtube.data.SeenLog
import dev.wahha.wtube.data.SeenVideo
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.player.NextVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Home and related videos leave out or move down. */
class RecommendationsTest {
    private fun video(id: String, duration: Long = 600, live: Boolean = false, upcoming: Boolean = false) =
        VideoItem(id, "t", null, "c", null, null, duration = duration, views = 1, uploaded = null, isLive = live, upcoming = upcoming)

    private val hours = 60 * 60 * 1000L
    private val days = 24 * hours

    // Written by hand after the extractor's reading of `upcomingEventData`: no real one turned up in a live search.
    @Test
    fun premiereInAListIsMarkedUpcoming() {
        val start = System.currentTimeMillis() / 1000 + 86_400
        val json = """{"contents":[{"videoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"Soon"}]},
            "ownerText":{"runs":[{"text":"Channel","navigationEndpoint":{"browseEndpoint":{"browseId":"UCx"}}}]},
            "upcomingEventData":{"startTime":"$start","isReminderSet":false}}}]}"""
        val items = with(YouTube) { FeedParser.page(JsonParser.`object`().from(json), null).items.toItems() }
        assertEquals(listOf(true), items.filterIsInstance<VideoItem>().map { it.upcoming })
    }

    @Test
    fun plainVideoInAListIsNotUpcoming() {
        val members = JsonParser.`object`().from(javaClass.getResourceAsStream("/innertube/members_only_list.json")!!.bufferedReader().readText())
        val items = with(YouTube) { FeedParser.page(members, null).items.toItems() }.filterIsInstance<VideoItem>()
        assertTrue(items.isNotEmpty())
        assertTrue(items.none { it.upcoming })
    }

    @Test
    fun watchedIsAlmostToTheEnd() {
        assertEquals(setOf("done", "nearly"), Recommendations.watchedIds(mapOf("done" to 1f, "nearly" to 0.9f, "half" to 0.5f)))
    }

    @Test
    fun watchedLeavesOnlyWhenAsked() {
        assertFalse(Recommendations.allows(video("a"), AppSettings(), setOf("a")))
        assertTrue(Recommendations.allows(video("b"), AppSettings(), setOf("a")))
        assertTrue(Recommendations.allows(video("a"), AppSettings(hideWatchedRecommended = false), setOf("a")))
    }

    @Test
    fun lengthLimitSparesUnknownLengths() {
        val settings = AppSettings(minDurationMinutes = 3)
        assertFalse(Recommendations.allows(video("short", duration = 179), settings, emptySet()))
        assertTrue(Recommendations.allows(video("exact", duration = 180), settings, emptySet()))
        assertTrue(Recommendations.allows(video("unknown", duration = 0), settings, emptySet()))
        assertTrue(Recommendations.allows(video("short", duration = 10), AppSettings(), emptySet()))
    }

    @Test
    fun streamsAndPremieresLeaveTogether() {
        val settings = AppSettings(hideStreams = true)
        assertFalse(Recommendations.allows(video("live", duration = 0, live = true), settings, emptySet()))
        assertFalse(Recommendations.allows(video("soon", duration = 0, upcoming = true), settings, emptySet()))
        assertTrue(Recommendations.allows(video("plain"), settings, emptySet()))
        assertTrue(Recommendations.allows(video("live", duration = 0, live = true), AppSettings(), emptySet()))
    }

    @Test
    fun otherItemsAreNotFiltered() {
        val channel = ChannelItem("https://www.youtube.com/channel/UCx", "c", null, 1, false)
        assertTrue(Recommendations.allows(channel, AppSettings(hideStreams = true, minDurationMinutes = 10), emptySet()))
    }

    @Test
    fun nextVideoSkipsWhatCannotPlayAndTheLengthLimit() {
        val related = listOf(video("soon", upcoming = true), video("short", duration = 30), video("long"))
        assertEquals(listOf("short", "long"), NextVideo.candidates(related, "x", HiddenItems()).map { it.id })
        assertEquals(listOf("long"), NextVideo.candidates(related, "x", HiddenItems(), minMinutes = 3).map { it.id })
    }

    @Test
    fun showingsCloseTogetherCountOnce() {
        val start = 100 * days
        val log = SeenLog().record(listOf("a"), start).record(listOf("a"), start + hours)
        assertEquals(SeenVideo(1, start), log.videos["a"])
        assertEquals(SeenVideo(2, start + 6 * hours), log.record(listOf("a"), start + 6 * hours).videos["a"])
    }

    @Test
    fun nothingNewKeepsTheSameLog() {
        val log = SeenLog().record(listOf("a"), 100 * days)
        assertSame(log, log.record(listOf("a"), 100 * days + hours))
        assertSame(log, log.record(emptyList(), 200 * days))
    }

    @Test
    fun tiredVideosGoToThePageEndInOrder() {
        val tired = SeenVideo(SeenLog.TIRED_AFTER, 0)
        val almost = SeenVideo(SeenLog.TIRED_AFTER - 1, 0)
        val channel = ChannelItem("https://www.youtube.com/channel/UCx", "c", null, 1, false)
        val items: List<Item> = listOf(video("a"), video("b"), channel, video("c"), video("d"))
        val log = SeenLog(mapOf("a" to tired, "b" to almost, "c" to tired))
        assertEquals(listOf(video("b"), channel, video("d"), video("a"), video("c")), log.demote(items))
    }

    @Test
    fun oldEntriesFadeAndTheLogIsBounded() {
        val now = 100 * days
        val old = SeenLog(mapOf("old" to SeenVideo(9, now - SeenLog.KEEP_MS), "recent" to SeenVideo(9, now - days)))
        assertEquals(setOf("recent", "new"), old.record(listOf("new"), now).videos.keys)

        val full = SeenLog((0 until SeenLog.MAX_VIDEOS).associate { "v$it" to SeenVideo(1, now - it * 1000L) })
        val after = full.record(listOf("new"), now).videos
        assertEquals(SeenLog.MAX_VIDEOS, after.size)
        assertTrue("new" in after)
        assertFalse("v${SeenLog.MAX_VIDEOS - 1}" in after)
    }
}
