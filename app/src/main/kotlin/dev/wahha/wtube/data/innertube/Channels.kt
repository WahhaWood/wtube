package dev.wahha.wtube.data.innertube

import java.net.URLDecoder
import java.util.Base64
import dev.wahha.wtube.data.MembersOnly
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

/** A chip over a channel's Videos, by YouTube's English [title] (see [InnerTube]). */
class VideoChip(val title: String, val token: String)

/**
 * What the channel page offers beyond the extractor: the chips over its Videos, the About panel's token,
 * and whether it has memberships. A channel with memberships gets other chips than one without: Latest,
 * Members only and Public, with no Popular or Oldest. Those two still work when asked for, see
 * [Channels.resorted]; Members only is left to the channel's members tab, which also holds its streams.
 */
class ChannelExtras(val videoSorts: List<VideoChip>, val aboutToken: String?, val joinable: Boolean = false)

/**
 * The parts of a channel page the extractor does not read: sorting its videos, searching in it, and the
 * About panel. All anonymous web requests; the account's session goes along only when signed in.
 */
object Channels {
    // The web client's tab params for Videos and for the channel search.
    private const val VIDEOS_PARAMS = "EgZ2aWRlb3PyBgQKAjoA"
    private const val SEARCH_PARAMS = "EgZzZWFyY2jyBgQKAloA"
    private const val MEMBERS_CHIP = "Members only"
    private const val LATEST_CHIP = "Latest"
    private const val POPULAR_CHIP = "Popular"
    private const val OLDEST_CHIP = "Oldest"

    // The end of a chip's token, still base64 and URL-encoded inside it: the sort field, 3 = 4, 2 or 5.
    private const val SORT_LATEST = "YBA%3D%3D"
    private const val SORT_POPULAR = "YAg%3D%3D"
    private const val SORT_OLDEST = "YBQ%3D%3D"
    private val JoinedFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH)

    suspend fun extras(channelId: String): ChannelExtras =
        extras(InnerTube.browse(Account.active, browseId = channelId, params = VIDEOS_PARAMS))

    /** The Videos tab reloaded with a token of [ChannelExtras.videoSorts]. */
    suspend fun sortedVideos(token: String): ItemsPage = Feeds.itemsPage(InnerTube.browse(Account.active, continuation = token))

    suspend fun search(channelId: String, query: String): ItemsPage =
        Feeds.itemsPage(InnerTube.browse(Account.active, browseId = channelId, params = SEARCH_PARAMS, query = query))

    /** The channel's members-only videos. A channel without any answers with an empty page, not an error. */
    suspend fun membersVideos(channelId: String): ItemsPage =
        Feeds.itemsPage(InnerTube.browse(Account.active, browseId = "VL" + MembersOnly.playlistId(channelId)))

    suspend fun about(token: String): ChannelAbout? = about(InnerTube.browse(Account.active, continuation = token))

    /** The chips in YouTube's order; the About panel's token from the header's description. */
    fun extras(page: JsonObject): ChannelExtras {
        val chips = FeedParser.findFirst(page["contents"], "chipBarViewModel")?.getArray("chips")?.filterIsInstance<JsonObject>().orEmpty()
        val description = FeedParser.findFirst(page["header"], "descriptionPreviewViewModel")
        val videoChips = chips.mapNotNull { chip ->
            val title = chip.getObject("chipViewModel").getString("text") ?: return@mapNotNull null
            FeedParser.findFirst(chip, "continuationCommand")?.getString("token")?.let { VideoChip(title, it) }
        }
        val memberships = videoChips.any { it.title == MEMBERS_CHIP }
        return ChannelExtras(
            videoSorts = if (memberships) withSorts(videoChips.filter { it.title != MEMBERS_CHIP }) else videoChips,
            aboutToken = FeedParser.findFirst(description, "continuationCommand")?.getString("token"),
            joinable = memberships ||
                FeedParser.findAll(page["header"], "buttonViewModel").any { it.getString("iconName") == "SPONSORSHIP_STAR" },
        )
    }

    // Popular and Oldest right after Latest, where every other channel has them.
    private fun withSorts(chips: List<VideoChip>): List<VideoChip> {
        val latest = chips.firstOrNull { it.title == LATEST_CHIP } ?: return chips
        val sorts = listOfNotNull(
            resorted(latest.token, SORT_POPULAR)?.let { VideoChip(POPULAR_CHIP, it) },
            resorted(latest.token, SORT_OLDEST)?.let { VideoChip(OLDEST_CHIP, it) },
        )
        return listOf(latest) + sorts + (chips - latest)
    }

    /**
     * The Latest chip's token with another sort: a channel with memberships offers no chip for it, yet
     * YouTube obeys. Null if the token is not what this expects, and then there is simply no such chip.
     */
    private fun resorted(latest: String, sort: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(URLDecoder.decode(latest, Charsets.UTF_8.name()))
        val text = String(bytes, Charsets.ISO_8859_1)
        if (!text.endsWith(SORT_LATEST)) return@runCatching null
        val sorted = text.removeSuffix(SORT_LATEST) + sort
        Base64.getUrlEncoder().encodeToString(sorted.toByteArray(Charsets.ISO_8859_1)).replace("=", "%3D")
    }.getOrNull()

    internal fun popularOf(latest: String) = resorted(latest, SORT_POPULAR)

    internal fun oldestOf(latest: String) = resorted(latest, SORT_OLDEST)

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
