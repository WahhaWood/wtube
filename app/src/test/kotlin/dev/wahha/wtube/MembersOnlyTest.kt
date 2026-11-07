package dev.wahha.wtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.MembersOnly
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.data.innertube.WatchParser
import dev.wahha.wtube.player.NextVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.PaidContentException

/** The marks and filters of videos for a channel's members; `members_only_*.json` are cut from real responses. */
class MembersOnlyTest {
    private fun fixture(name: String): JsonObject =
        JsonParser.`object`().from(javaClass.getResourceAsStream("/innertube/$name")!!.bufferedReader().readText())

    private fun video(id: String, members: Boolean) =
        VideoItem(id, "t", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = false, membersOnly = members)

    private val members = video("m", true)
    private val open = video("o", false)

    @Test
    fun listsMarkTheVideosYouTubeMarks() {
        val items = with(YouTube) { FeedParser.page(fixture("members_only_list.json"), null).items.toItems() }.filterIsInstance<VideoItem>()
        assertEquals(listOf(true, true, false), items.map { it.membersOnly })
        assertEquals("B-AGO9R8t-A", items.first().id)
    }

    @Test
    fun filterLeavesThemOutStepByStep() {
        fun shown(filter: Int, place: (Item, Int) -> Boolean) = listOf(members, open).filter { place(it, filter) }.map { it.id }
        assertEquals(listOf("m", "o"), shown(MembersOnly.SHOW, MembersOnly::inRecommendations))
        assertEquals(listOf("m", "o"), shown(MembersOnly.SHOW, MembersOnly::inFeeds))
        assertEquals(listOf("o"), shown(MembersOnly.HIDE_RECOMMENDED, MembersOnly::inRecommendations))
        assertEquals(listOf("m", "o"), shown(MembersOnly.HIDE_RECOMMENDED, MembersOnly::inFeeds))
        assertEquals(listOf("o"), shown(MembersOnly.HIDE_FEEDS, MembersOnly::inRecommendations))
        assertEquals(listOf("o"), shown(MembersOnly.HIDE_FEEDS, MembersOnly::inFeeds))
    }

    @Test
    fun recommendationsLeaveThemOutUnlessAsked() {
        val filter = AppSettings().membersOnly
        assertFalse(MembersOnly.inRecommendations(members, filter))
        assertTrue(MembersOnly.inRecommendations(open, filter))
        assertTrue(MembersOnly.inFeeds(members, filter))
    }

    @Test
    fun nextVideoNeverPicksOne() {
        assertEquals(listOf("o"), NextVideo.candidates(listOf(members, open), "x", HiddenItems()).map { it.id })
    }

    // The extractor says "members" only for a membership; a video on sale says "paid".
    @Test
    fun refusalIsToldFromAPaidVideo() {
        val refusal = PaidContentException("This video is only available for members of the channel of this video")
        assertTrue(MembersOnly.refused(refusal))
        assertTrue(MembersOnly.refused(RuntimeException(refusal)))
        assertFalse(MembersOnly.refused(PaidContentException("This video is a paid video")))
        assertFalse(MembersOnly.refused(RuntimeException("members")))
    }

    @Test
    fun watchPageNamesARefusedVideo() {
        val video = WatchParser.watch(fixture("members_only_next.json")).video!!
        assertEquals("Новогоднее обращение для спонсоров канала", video.title)
        assertEquals("varlamov", video.channelName)
        assertEquals("https://www.youtube.com/channel/UC101o-vQ2iOj9vr00JUlyKw", video.channelUrl)
    }

    @Test
    fun channelHasMembershipsByTheJoinButton() {
        val header = """{"header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{"actions":{"flexibleActionsViewModel":{"actionsRows":[{"actions":[
            {"buttonViewModel":{"title":"Subscribe"}},{"buttonViewModel":{"iconName":"SPONSORSHIP_STAR","title":"Join"}}]}]}}}}}}}"""
        assertTrue(Channels.extras(JsonParser.`object`().from(header)).joinable)
        assertFalse(Channels.extras(fixture("channel_about.json")).joinable)
    }

    // A channel with memberships has other chips than Latest, Popular and Oldest: telling them by place
    // called the members-only list "Popular" and the public one "Oldest".
    @Test
    fun chipsOfAChannelWithMembershipsAreToldByName() {
        fun chip(title: String) = """{"chipViewModel":{"text":"$title","tapCommand":{"innertubeCommand":{"continuationCommand":{"token":"t-$title"}}}}}"""
        val page = """{"contents":{"chipBarViewModel":{"chips":[${chip("Latest")},${chip("Members only")},${chip("Public")}]}}}"""
        val extras = Channels.extras(JsonParser.`object`().from(page))
        assertEquals(listOf("Latest", "Public"), extras.videoSorts.map { it.title })
        assertEquals("t-Public", extras.videoSorts[1].token)
        assertTrue(extras.joinable)
    }

    // The sorts a channel with memberships gets no chip for: computed from Latest, they must be exactly
    // the tokens YouTube itself gives every other channel, here from a real response.
    @Test
    fun computedSortsAreTheTokensYouTubeGives() {
        val real = Channels.extras(fixture("channel_videos.json")).videoSorts.associate { it.title to it.token }
        val latest = real.getValue("Latest")
        assertEquals(real.getValue("Popular"), Channels.popularOf(latest))
        assertEquals(real.getValue("Oldest"), Channels.oldestOf(latest))
    }

    @Test
    fun channelWithMembershipsGetsTheSortsBackInPlace() {
        val latest = Channels.extras(fixture("channel_videos.json")).videoSorts.first().token
        fun chip(title: String, token: String) = """{"chipViewModel":{"text":"$title","tapCommand":{"innertubeCommand":{"continuationCommand":{"token":"$token"}}}}}"""
        val page = """{"contents":{"chipBarViewModel":{"chips":[${chip("Latest", latest)},${chip("Members only", "m")},${chip("Public", "p")}]}}}"""
        assertEquals(listOf("Latest", "Popular", "Oldest", "Public"), Channels.extras(JsonParser.`object`().from(page)).videoSorts.map { it.title })
    }

    // A token of another form must not turn into a wrong sort: no chip instead.
    @Test
    fun unknownTokenGetsNoComputedSort() {
        assertEquals(null, Channels.popularOf("not a token"))
        assertEquals(null, Channels.oldestOf("dGV4dA%3D%3D"))
    }

    @Test
    fun urlsOfAChannel() {
        assertEquals("UUMO101o-vQ2iOj9vr00JUlyKw", MembersOnly.playlistId("UC101o-vQ2iOj9vr00JUlyKw"))
        assertEquals("https://www.youtube.com/channel/UCx/join", MembersOnly.joinUrl("https://www.youtube.com/channel/UCx/"))
    }
}
