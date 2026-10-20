package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.account.Account
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A channel's About panel. YouTube writes it in English (see [InnerTube]); numbers and dates are parsed out. */
class ChannelAbout(
    val description: String,
    /** -1 when unknown. */
    val views: Long,
    /** -1 when unknown. */
    val videos: Long,
    val joined: LocalDate?,
    /** In Russian when the English name is a known country. */
    val country: String?,
    val links: List<ChannelLink>,
)

class ChannelLink(val title: String, val url: String)

/** What the channel page offers beyond the extractor: sort tokens of the Videos tab and the About panel's token. */
class ChannelExtras(val videoSorts: List<String>, val aboutToken: String?)

/**
 * The parts of a channel page the extractor does not read: sorting its videos, searching in it, and the
 * About panel. All anonymous web requests; the account's session goes along only when signed in.
 */
object Channels {
    // The web client's tab params for Videos and for the channel search.
    private const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"
    private val JoinedFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    suspend fun extras(channelId: String): ChannelExtras =
        extras(InnerTube.browse(Account.active, browseId = channelId, params = VIDEOS_PARAMS))

    /** The Videos tab reloaded with a token of [ChannelExtras.videoSorts]. */
    suspend fun sortedVideos(token: String): ItemsPage = Feeds.itemsPage(InnerTube.browse(Account.active, continuation = token))

    suspend fun search(channelId: String, query: String): ItemsPage =
        Feeds.itemsPage(InnerTube.browse(Account.active, browseId = channelId, params = SEARCH_PARAMS, query = query))

    suspend fun about(token: String): ChannelAbout? = about(InnerTube.browse(Account.active, continuation = token))

    /** Latest, Popular, Oldest in YouTube's chip order; the About panel's token from the header's description. */
    fun extras(page: JsonObject): ChannelExtras {
        val chips = FeedParser.findFirst(page["contents"], "chipBarViewModel")?.getArray("chips")?.filterIsInstance<JsonObject>().orEmpty()
        val description = FeedParser.findFirst(page["header"], "descriptionPreviewViewModel")
        return ChannelExtras(
            videoSorts = chips.mapNotNull { FeedParser.findFirst(it, "continuationCommand")?.getString("token") },
            aboutToken = FeedParser.findFirst(description, "continuationCommand")?.getString("token"),
        )
    }

    fun about(response: JsonObject): ChannelAbout? {
        val about = FeedParser.findFirst(response, "aboutChannelViewModel") ?: return null
        return ChannelAbout(
            description = about.getString("description").orEmpty(),
            views = number(about.getString("viewCountText")),
            videos = number(about.getString("videoCountText")),
            joined = about.getObject("joinedDateText").getString("content")?.removePrefix("Joined ")
                ?.let { runCatching { LocalDate.parse(it, JoinedFormat) }.getOrNull() },
            country = about.getString("country")?.let(::countryName),
            links = about.getArray("links").filterIsInstance<JsonObject>().mapNotNull { link ->
                val model = link.getObject("channelExternalLinkViewModel")
                val title = model.getObject("title").getString("content") ?: return@mapNotNull null
                // The shown address, not the youtube.com/redirect one behind it.
                val address = model.getObject("link").getString("content") ?: return@mapNotNull null
                ChannelLink(title, if (address.startsWith("http")) address else "https://$address")
            },
        )
    }

    // "2,713,179,977 views" → 2713179977; -1 without digits.
    private fun number(text: String?): Long = text?.filter(Char::isDigit)?.toLongOrNull() ?: -1

    private fun countryName(english: String): String {
        val code = Locale.getISOCountries().firstOrNull { Locale("", it).getDisplayCountry(Locale.ENGLISH).equals(english, ignoreCase = true) }
        return code?.let { Locale("", it).getDisplayCountry(Locale.forLanguageTag("ru")) } ?: english
    }
}
