package dev.wahha.wtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.account.Cookies
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.data.innertube.LikeStatus
import dev.wahha.wtube.data.innertube.PlaylistOption
import dev.wahha.wtube.data.innertube.VideoState
import dev.wahha.wtube.data.innertube.WatchParser
import dev.wahha.wtube.player.WatchReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import java.time.LocalDate

/**
 * Sign-in cookies and parsing of InnerTube responses. The fixtures are anonymous responses of public
 * pages cut down and arranged in the shapes signed-in feeds use; the account ones are made up.
 */
class InnerTubeTest {
    private val timeAgo = ServiceList.YouTube.getTimeAgoParser(Localization("en", "US"))

    private fun fixture(name: String): JsonObject =
        JsonParser.`object`().from(javaClass.getResourceAsStream("/innertube/$name")!!.bufferedReader().readText())

    @Test
    fun cookieHeaderRoundTrips() {
        val cookies = Cookies.parse("Cookie: SID=a; HSID=b=c ;  SAPISID=s")
        assertEquals(mapOf("SID" to "a", "HSID" to "b=c", "SAPISID" to "s"), cookies)
        assertEquals("SID=a; HSID=b=c; SAPISID=s", Cookies.format(cookies))
    }

    @Test
    fun netscapeFileKeepsOnlyYouTube() {
        val file = """
            # Netscape HTTP Cookie File
            .youtube.com	TRUE	/	TRUE	1999999999	SAPISID	s1
            #HttpOnly_.youtube.com	TRUE	/	TRUE	1999999999	SID	sid
            .google.com	TRUE	/	TRUE	1999999999	SID	other
        """.trimIndent()
        assertEquals(mapOf("SAPISID" to "s1", "SID" to "sid"), Cookies.fromUserInput(file))
    }

    /** Known vectors: sha1("1700000000 <cookie> https://www.youtube.com"), computed outside the app. */
    @Test
    fun sapisidHashMatchesWebClient() {
        val auth = Cookies.authorization(
            mapOf("SAPISID" to "SAPISIDvalue", "__Secure-1PAPISID" to "P1value", "__Secure-3PAPISID" to "P3value"),
            timeSeconds = 1_700_000_000,
        )
        assertEquals(
            "SAPISIDHASH 1700000000_0a4ca0ae5507d5981e6bb5ebe0f2ce2ab998ee51 " +
                "SAPISID1PHASH 1700000000_ebc946ab29bb03dfbf8814fa6f0018e17e70954b " +
                "SAPISID3PHASH 1700000000_66da0ec98868e7c39211f1b728c1dd9b9a8a72c8",
            auth,
        )
        assertNull(Cookies.authorization(mapOf("SID" to "a"), 1))
        assertFalse(Cookies.canSign(mapOf("SID" to "a")))
        assertTrue(Cookies.canSign(mapOf("__Secure-3PAPISID" to "x")))
    }

    @Test
    fun homeFeedDropsShortsAndAds() {
        val page = FeedParser.page(fixture("home.json"), timeAgo)
        val ids = page.items.toItems().map { (it as VideoItem).id }
        // Grid order; the Shorts shelf, the reel shelf and the ad (which hides a video) are gone.
        assertEquals(listOf("PVSOJyG5WKU", "fOT0BUpITw8", "vadHXNBy4jQ"), ids)
        // The list's own continuation, not the filter chip's.
        assertEquals("HOME_NEXT", page.continuation)
    }

    @Test
    fun continuationPageAppends() {
        val page = FeedParser.page(fixture("home_more.json"), timeAgo)
        assertEquals(listOf("NFvDHYMzj9U"), page.items.toItems().map { (it as VideoItem).id })
        assertNull(page.continuation)
    }

    @Test
    fun playlistPageHasItemsTitleAndListContinuation() {
        val response = fixture("playlist.json")
        val page = FeedParser.page(response, timeAgo)
        val videos = page.items.toItems().filterIsInstance<VideoItem>()
        assertEquals(3, videos.size)
        assertTrue(videos.all { it.title.isNotBlank() && it.channelName != null && it.duration > 0 })
        assertEquals("Popular Music Videos", FeedParser.playlistTitle(response))
        // The item list's continuation comes first; the section list's second one loads other shelves.
        assertTrue(page.continuation!!.startsWith("4qmFsgKBARIkVkxQTEZncXVMbkw1OWFsQ2xf"))
    }

    @Test
    fun channelListParses() {
        val channels = FeedParser.page(fixture("channels.json"), timeAgo).items.toItems()
        val channel = channels.single() as ChannelItem
        assertTrue(channel.url.endsWith("UCSJ4gkVC6NrvII8umztf0Ow"))
        assertTrue(channel.name.isNotBlank())
    }

    @Test
    fun accountMenuAndSwitcher() {
        val me = FeedParser.activeAccount(fixture("account_menu.json"))!!
        assertEquals("Test User", me.name)
        assertEquals("@testuser", me.byline)
        assertEquals("https://yt3.ggpht.example/a=s88", me.avatar)

        val switcher = javaClass.getResourceAsStream("/innertube/account_switcher.json")!!.bufferedReader().readText()
        val identities = FeedParser.identities(JsonParser.`object`().from(switcher))
        assertEquals(2, identities.size)
        val (own, brand) = identities
        assertEquals(0, own.authUser)
        assertNull(own.pageId)
        assertTrue(own.selected)
        assertEquals("Brand Channel", brand.name)
        assertEquals(1, brand.authUser)
        assertEquals("111222333", brand.pageId)
        assertEquals("https://yt3.ggpht.example/b=s88", brand.avatar)
    }

    @Test
    fun watchStateFromNext() {
        val watch = WatchParser.watch(fixture("watch_next.json"))
        assertEquals(VideoState(LikeStatus.LIKE, "UCuAXFkgsw1L7xaCfnd5JJOw", subscribed = true), watch.state)
        assertTrue(watch.commentsToken!!.isNotBlank())
    }

    @Test
    fun mostReplayedHeatmapInStartOrder() {
        val heatmap = WatchParser.watch(fixture("watch_next.json")).heatmap
        val expected = listOf(1f, 0.423f, 0.353f, 0.301f)
        assertEquals(expected.size, heatmap.size)
        expected.zip(heatmap).forEach { (e, a) -> assertEquals(e, a, 0.001f) }
    }

    @Test
    fun commentsCarryAccountActions() {
        val page = WatchParser.comments(fixture("comments_page.json"), timeAgo)
        assertEquals("create-params", page.createParams)
        assertTrue(page.continuation!!.isNotBlank())
        val (first, second) = page.comments
        assertEquals("UgzKYksqeQ5gA3t0g0F4AaABAg", first.id)
        assertEquals("@grustache4569", first.author)
        assertEquals("https://www.youtube.com/channel/UCKrzgYonJnMd0Dk7zQXjYXw", first.authorUrl)
        assertEquals(104_000, first.likes)
        assertEquals(621, first.replies)
        assertNotNull(first.postedAt)
        val actions = first.actions!!
        assertTrue(actions.liked)
        assertEquals("like-token", actions.like)
        assertEquals("unlike-token", actions.unlike)
        assertEquals("reply-params", actions.replyParams)
        // The second one is as an anonymous request sees it: nothing to act with.
        assertNull(second.actions)
        assertEquals(
            "At <a href=\"https://www.youtube.com/watch?v=dQw4w9WgXcQ&amp;t=43s\">0:43</a> &lt;3 see " +
                "<a href=\"https://example.com\">https://example.com</a><br>bye",
            second.html,
        )
    }

    @Test
    fun playlistOptionsMarkWhereTheVideoIs() {
        assertEquals(
            listOf(
                PlaylistOption("WL", "Watch later", contains = false, privacy = "PRIVATE"),
                PlaylistOption("PLmusic", "Музыка", contains = true, privacy = "PUBLIC"),
            ),
            WatchParser.playlistOptions(fixture("add_to_playlist.json")),
        )
    }

    @Test
    fun trackingUrlsFromWatchPage() {
        val html = """<script>var ytInitialPlayerResponse = {"playbackTracking":{""" +
            """"videostatsPlaybackUrl":{"baseUrl":"https://s.youtube.com/api/stats/playback?cl=1\u0026docid=abc\u0026len=214"},""" +
            """"videostatsWatchtimeUrl":{"baseUrl":"https://s.youtube.com/api/stats/watchtime?docid=abc\u0026len=214"}}};</script>"""
        val tracking = WatchParser.tracking(html)!!
        assertEquals("https://s.youtube.com/api/stats/playback?cl=1&docid=abc&len=214", tracking.playbackUrl)
        assertEquals("https://s.youtube.com/api/stats/watchtime?docid=abc&len=214", tracking.watchtimeUrl)
        assertNull(WatchParser.tracking("<html>no player</html>"))
    }

    @Test
    fun clientPlaybackNonce() {
        repeat(20) { assertTrue(WatchReporter.cpn().matches(Regex("[A-Za-z0-9_-]{16}"))) }
        assertEquals("12.500", WatchReporter.seconds(12.5))
    }

    @Test
    fun channelVideosOfferSortsAndAbout() {
        val page = fixture("channel_videos.json")
        val extras = Channels.extras(page)
        assertEquals(3, extras.videoSorts.size)
        assertNotNull(extras.aboutToken)
        assertEquals(3, FeedParser.page(page, timeAgo).items.toItems().filterIsInstance<VideoItem>().size)
    }

    @Test
    fun channelSearchFindsVideos() {
        val items = FeedParser.page(fixture("channel_search.json"), timeAgo).items.toItems()
        assertEquals(3, items.filterIsInstance<VideoItem>().size)
    }

    @Test
    fun channelAboutParsesNumbersDateCountryLinks() {
        val about = Channels.about(fixture("channel_about.json"))!!
        assertTrue(about.description.isNotBlank())
        assertEquals(2_713_179_977, about.views)
        assertEquals(472, about.videos)
        assertEquals(LocalDate.of(2015, 3, 18), about.joined)
        assertEquals("Франция", about.country)
        assertEquals(ChannelLinkView("Discord", "https://discord.com/invite/hUKvJnw"), about.links.first().let { ChannelLinkView(it.title, it.url) })
    }

    private data class ChannelLinkView(val title: String, val url: String)
}
