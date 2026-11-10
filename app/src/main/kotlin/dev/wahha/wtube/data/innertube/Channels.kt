package dev.wahha.wtube.data.innertube

import java.net.URLDecoder
import java.util.Base64
import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.MembersOnly
import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.account.Account
import java.time.LocalDate
import java.util.Locale

/** A channel's About panel, in the language YouTube was asked in (see [dev.wahha.wtube.data.YouTubeLanguage]); numbers and dates are parsed out. */
class ChannelAbout(
    val description: String,
    /** -1 when unknown. */
    val views: Long,
    /** -1 when unknown. */
    val videos: Long,
    val joined: LocalDate?,
    /** As YouTube gives it, in English or Russian; [countryName] puts it into the interface language. */
    val country: String?,
    val links: List<ChannelLink>,
)

class ChannelLink(val title: String, val url: String)

/** What a chip over a channel's Videos does, told apart without its title where the token says it. */
enum class ChipKind { LATEST, POPULAR, OLDEST, PUBLIC }

/** A chip over a channel's Videos: shown by [kind] when the app knows it, otherwise by YouTube's [title]. */
class VideoChip(val title: String, val token: String, val kind: ChipKind? = Channels.kindOf(title, token))

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
    // Filters have no sort field of their own, so these two go by title, in both languages YouTube is asked in.
    private val MembersChip = setOf("Members only", "Только для спонсоров")
    private val PublicChip = setOf("Public", "Для всех")

    // The end of a chip's token, still base64 and URL-encoded inside it: the sort field, 3 = 4, 2 or 5.
    private const val SORT_LATEST = "YBA%3D%3D"
    private const val SORT_POPULAR = "YAg%3D%3D"
    private const val SORT_OLDEST = "YBQ%3D%3D"

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
        val memberships = videoChips.any { it.title in MembersChip }
        return ChannelExtras(
            videoSorts = if (memberships) withSorts(videoChips.filter { it.title !in MembersChip }) else videoChips,
            aboutToken = FeedParser.findFirst(description, "continuationCommand")?.getString("token"),
            joinable = memberships ||
                FeedParser.findAll(page["header"], "buttonViewModel").any { it.getString("iconName") == "SPONSORSHIP_STAR" },
        )
    }

    // Popular and Oldest right after Latest, where every other channel has them.
    private fun withSorts(chips: List<VideoChip>): List<VideoChip> {
        val latest = chips.firstOrNull { it.kind == ChipKind.LATEST } ?: return chips
        val sorts = listOfNotNull(
            resorted(latest.token, SORT_POPULAR)?.let { VideoChip("", it, ChipKind.POPULAR) },
            resorted(latest.token, SORT_OLDEST)?.let { VideoChip("", it, ChipKind.OLDEST) },
        )
        return listOf(latest) + sorts + (chips - latest)
    }

    /**
     * The Latest chip's token with another sort: a channel with memberships offers no chip for it, yet
     * YouTube obeys. Null if the token is not what this expects, and then there is simply no such chip.
     */
    private fun resorted(latest: String, sort: String): String? = runCatching {
        val text = decoded(latest)
        if (!text.endsWith(SORT_LATEST)) return@runCatching null
        val sorted = text.removeSuffix(SORT_LATEST) + sort
        Base64.getUrlEncoder().encodeToString(sorted.toByteArray(Charsets.ISO_8859_1)).replace("=", "%3D")
    }.getOrNull()

    private fun decoded(token: String): String =
        String(Base64.getUrlDecoder().decode(URLDecoder.decode(token, Charsets.UTF_8.name())), Charsets.ISO_8859_1)

    /** A sort chip by the sort field at the end of its token, whatever language its title is in. */
    internal fun kindOf(title: String, token: String): ChipKind? {
        if (title in PublicChip) return ChipKind.PUBLIC
        val text = runCatching { decoded(token) }.getOrNull() ?: return null
        return when {
            text.endsWith(SORT_LATEST) -> ChipKind.LATEST
            text.endsWith(SORT_POPULAR) -> ChipKind.POPULAR
            text.endsWith(SORT_OLDEST) -> ChipKind.OLDEST
            else -> null
        }
    }

    internal fun popularOf(latest: String) = resorted(latest, SORT_POPULAR)

    internal fun oldestOf(latest: String) = resorted(latest, SORT_OLDEST)

    fun about(response: JsonObject): ChannelAbout? {
        val about = FeedParser.findFirst(response, "aboutChannelViewModel") ?: return null
        return ChannelAbout(
            description = about.getString("description").orEmpty(),
            views = number(about.getString("viewCountText")),
            videos = number(about.getString("videoCountText")),
            joined = about.getObject("joinedDateText").getString("content")?.let(::joinedDate),
            country = about.getString("country"),
            links = about.getArray("links").filterIsInstance<JsonObject>().mapNotNull { link ->
                val model = link.getObject("channelExternalLinkViewModel")
                val title = model.getObject("title").getString("content") ?: return@mapNotNull null
                // The shown address, not the youtube.com/redirect one behind it.
                val address = model.getObject("link").getString("content") ?: return@mapNotNull null
                ChannelLink(title, if (address.startsWith("http")) address else "https://$address")
            },
        )
    }

    // "2,713,179,977 views" or "2 713 179 977 просмотров" → 2713179977; -1 without digits.
    private fun number(text: String?): Long = text?.filter(Char::isDigit)?.toLongOrNull() ?: -1

    /** "Joined Mar 18, 2015" or "Дата регистрации: 18 мар. 2015 г." → 2015-03-18; null for anything else. */
    internal fun joinedDate(text: String): LocalDate? {
        val year = Year.find(text)?.value?.toInt() ?: return null
        val rest = text.replace(Year, " ")
        val day = Day.find(rest)?.value?.toInt() ?: return null
        val month = Word.findAll(rest).firstNotNullOfOrNull { word ->
            val w = word.value.lowercase()
            Months.indexOfFirst { names -> names.any { w.startsWith(it) } }.takeIf { it >= 0 }?.plus(1)
        } ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    private val Year = Regex("\\b\\d{4}\\b")
    private val Day = Regex("\\b\\d{1,2}\\b")
    private val Word = Regex("\\p{L}+")
    // English and Russian month names by their start; "мая" is May in "18 мая 2015".
    private val Months = listOf(
        listOf("jan", "янв"), listOf("feb", "фев"), listOf("mar", "мар"), listOf("apr", "апр"),
        listOf("may", "мая", "май"), listOf("jun", "июн"), listOf("jul", "июл"), listOf("aug", "авг"),
        listOf("sep", "сен"), listOf("oct", "окт"), listOf("nov", "ноя"), listOf("dec", "дек"),
    )
}

/**
 * A country's name as YouTube gives it, in English or Russian, put into [locale]; unknown names stay as they
 * are (YouTube and Java can name a country differently, and then it is still in the language it was asked in).
 */
fun countryName(name: String, locale: Locale = AppLocale): String {
    val code = Locale.getISOCountries().firstOrNull { code ->
        NameLanguages.any { Locale("", code).getDisplayCountry(it).equals(name, ignoreCase = true) }
    }
    return code?.let { Locale("", it).getDisplayCountry(locale) } ?: name
}

private val NameLanguages = listOf(Locale.ENGLISH, Locale("ru"))
