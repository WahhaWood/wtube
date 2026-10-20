package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.PlaylistDetails
import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.SessionExpiredException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList

/**
 * The signed-in account's own lists. Each call throws [SessionExpiredException] when there is no
 * session or YouTube stopped accepting it; callers fall back to local lists. Nothing here refreshes on
 * its own: every request is one the user asked for, which keeps YouTube from suspecting a bot.
 */
object Feeds {
    const val HOME = "FEwhat_to_watch"
    const val SUBSCRIPTIONS = "FEsubscriptions"
    const val CHANNELS = "FEchannels"
    const val HISTORY = "FEhistory"
    // The web "Playlists" page; FElibrary used to hold it.
    const val PLAYLISTS = "FEplaylist_aggregation"
    const val WATCH_LATER = "WL"
    const val LIKED = "LL"

    // A continuation token travels as a Page id; this url tells it from an extractor cursor.
    private const val CURSOR = "wtube:innertube"

    fun playlistUrl(id: String) = "https://www.youtube.com/playlist?list=$id"

    fun isCursor(page: Page) = page.url == CURSOR

    fun cursor(token: String) = Page(CURSOR, token)

    /** The first page of [browseId], or the page [page] points to. */
    suspend fun feed(browseId: String, page: Page? = null): ItemsPage {
        val session = Account.active ?: throw SessionExpiredException()
        val response = if (page == null) InnerTube.browse(session, browseId = browseId) else InnerTube.browse(session, continuation = page.id)
        return itemsPage(response)
    }

    /** The page a cursor of any of these lists points to; the channel lists of [Channels] need no account. */
    suspend fun more(page: Page): ItemsPage = itemsPage(InnerTube.browse(Account.active, continuation = page.id))

    /** The items of a browse response and the cursor of its next page. */
    internal fun itemsPage(response: JsonObject): ItemsPage {
        val parsed = FeedParser.page(response, timeAgoParser())
        return ItemsPage(parsed.items.toItems(), parsed.continuation?.let { Page(CURSOR, it) })
    }

    /** The account's playlists, without Watch later and Liked: the Library lists those on their own. */
    suspend fun playlists(page: Page? = null): ItemsPage = feed(PLAYLISTS, page).let { result ->
        ItemsPage(result.items.filterNot { it is PlaylistItem && YouTube.playlistIdOf(it.url) in setOf(WATCH_LATER, LIKED) }, result.next)
    }

    /** Any playlist as the account sees it, private ones and Watch later included. */
    suspend fun playlist(url: String): PlaylistDetails {
        val id = YouTube.playlistIdOf(url) ?: throw IllegalArgumentException("Not a playlist link")
        val session = Account.active ?: throw SessionExpiredException()
        val response = InnerTube.browse(session, browseId = "VL$id")
        val parsed = FeedParser.page(response, timeAgoParser())
        val items = parsed.items.toItems()
        return PlaylistDetails(
            url = url,
            name = FeedParser.playlistTitle(response).orEmpty(),
            thumbnail = items.firstNotNullOfOrNull { (it as? VideoItem)?.thumbnail },
            uploader = null,
            count = -1,
            firstPage = ItemsPage(items, parsed.continuation?.let { Page(CURSOR, it) }),
        )
    }

    private fun timeAgoParser() = ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization())
}
