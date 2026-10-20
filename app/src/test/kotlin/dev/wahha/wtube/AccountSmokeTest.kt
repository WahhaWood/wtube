package dev.wahha.wtube

import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.account.Cookies
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.data.innertube.InnerTube
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import java.io.File

/**
 * The account feeds against YouTube with a real session: `WTUBE_COOKIES` holds a Cookie header or the
 * path of a cookies.txt. Skipped without it. Prints only counts, never names or titles.
 */
class AccountSmokeTest {
    private val source = System.getenv("WTUBE_COOKIES")

    @Before
    fun setUp() {
        assumeTrue("WTUBE_COOKIES not set", !source.isNullOrBlank())
        YouTube.init("RU")
    }

    private fun session(): Session {
        val text = File(source!!).takeIf { it.isFile }?.readText() ?: source
        return Session(cookie = Cookies.format(Cookies.fromUserInput(text)))
    }

    @Test
    fun signedInFeedsParse() = runBlocking {
        val session = session()
        assertNotNull("YouTube does not see these cookies as signed in", InnerTube.whoAmI(session))
        val timeAgo = ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization())
        for (id in listOf(Feeds.HOME, Feeds.SUBSCRIPTIONS, Feeds.CHANNELS, Feeds.HISTORY, Feeds.PLAYLISTS, "VL${Feeds.WATCH_LATER}", "VL${Feeds.LIKED}")) {
            val page = FeedParser.page(InnerTube.browse(session, browseId = id), timeAgo)
            val items = page.items.toItems()
            println("$id: ${items.size} items, next page: ${page.continuation != null}")
            assertFalse("$id let a short through", items.any { it is VideoItem && "/shorts/" in it.url })
        }
        println("identities: ${InnerTube.identities(session).size}")
    }

    /** Read-only: what the account's buttons start from. Nothing gets liked, saved or posted. */
    @Test
    fun accountViewOfVideoParses() = runBlocking {
        val session = session()
        val video = "dQw4w9WgXcQ"
        val watch = Actions.watch(video, session)
        val state = watch.state!!
        println("rating: ${state.like}, subscription known: ${state.subscribed != null}, channel: ${state.channelId != null}")
        assertNotNull("no subscription state on the watch page", state.subscribed)
        assertNotNull("no channel id on the watch page", state.channelId)
        println("channel page subscription known: ${Actions.subscribed(state.channelId!!, session) != null}")

        val comments = Actions.comments(watch.commentsToken!!, null, session)
        println("comments: ${comments.comments.size}, can post: ${comments.createParams != null}, with actions: ${comments.comments.count { it.actions != null }}")
        assertTrue("no comment loaded through the account", comments.comments.isNotEmpty())

        val options = Actions.playlistOptions(video, session)
        println("playlists: ${options.size}, containing it: ${options.count { it.contains }}")
        assertTrue("Watch later missing from Save to", options.any { it.id == Actions.WATCH_LATER })

        assertNotNull("no stats URLs on the watch page", Actions.tracking(video, session))
    }
}
