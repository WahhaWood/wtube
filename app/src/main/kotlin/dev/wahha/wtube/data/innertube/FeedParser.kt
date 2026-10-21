package dev.wahha.wtube.data.innertube

import android.util.Log
import com.grack.nanojson.JsonArray
import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.account.Identity
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItemExtractor
import org.schabi.newpipe.extractor.MultiInfoItemsCollector
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeChannelInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeMixOrPlaylistLockupInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubePlaylistInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemExtractor
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamInfoItemLockupExtractor

/**
 * What one browse response holds: its items in page order, the token of the next page, and where the
 * account stopped in the videos it started, by video id.
 */
class FeedPage(val items: List<InfoItem>, val continuation: String?, val resume: Map<String, ResumeMark> = emptyMap())

/** The account's place in a video as a list shows it: the second it resumes at and the red bar's percent (either may be missing). */
data class ResumeMark(val startSeconds: Int, val percent: Int)

/**
 * Reads InnerTube browse responses without trusting their layout: walks the whole content tree, hands
 * every item renderer to the extractor's own item parser, and drops Shorts and ads as whole subtrees.
 * Home, subscriptions, history and playlists nest their items differently and change often; what stays
 * put is the item renderers themselves, and those the extractor keeps up to date.
 */
object FeedParser {
    // Roots of a browse or continuation response that hold content; header, sidebar and topbar do not.
    private val ContentRoots = listOf("contents", "onResponseReceivedActions", "onResponseReceivedCommands", "continuationContents")

    // Subtrees dropped whole: Shorts shelves and items, ads, filter chips.
    private val Skipped = setOf(
        "reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel",
        "adSlotRenderer", "promotedSparklesWebRenderer", "statementBannerRenderer",
        "feedFilterChipBarRenderer", "chipCloudRenderer",
    )

    private val StreamRenderers = setOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer")
    private val ChannelRenderers = setOf("channelRenderer", "gridChannelRenderer")
    private val PlaylistRenderers = setOf("playlistRenderer", "gridPlaylistRenderer")
    private val ContinuationRenderers = setOf("continuationItemRenderer", "continuationItemViewModel")

    fun page(response: JsonObject, timeAgoParser: TimeAgoParser?): FeedPage {
        val collector = MultiInfoItemsCollector(ServiceList.YouTube.serviceId)
        var continuation: String? = null
        val resume = HashMap<String, ResumeMark>()

        fun mark(id: String?, mark: ResumeMark) {
            if (id != null && (mark.startSeconds > 0 || mark.percent > 0)) resume[id] = mark
        }

        // One item the extractor cannot read must not cost the whole page.
        fun commit(key: String, extract: () -> InfoItemExtractor) {
            try {
                collector.commit(extract())
            } catch (e: RuntimeException) {
                Log.w("FeedParser", "Skipped $key: $e")
            }
        }

        fun walk(node: Any?) {
            when (node) {
                is JsonArray -> node.forEach(::walk)
                is JsonObject -> for ((key, value) in node) {
                    if (key in Skipped || value !is JsonObject && value !is JsonArray) continue
                    val obj = value as? JsonObject
                    when {
                        obj == null -> walk(value)
                        key in StreamRenderers -> {
                            commit(key) { YoutubeStreamInfoItemExtractor(obj, timeAgoParser) }
                            mark(obj.getString("videoId"), rendererMark(obj))
                        }
                        key == "lockupViewModel" -> when (obj.getString("contentType")) {
                            "LOCKUP_CONTENT_TYPE_VIDEO" -> {
                                commit(key) { YoutubeStreamInfoItemLockupExtractor(obj, timeAgoParser) }
                                mark(obj.getString("contentId"), lockupMark(obj))
                            }
                            "LOCKUP_CONTENT_TYPE_PLAYLIST", "LOCKUP_CONTENT_TYPE_PODCAST" ->
                                commit(key) { YoutubeMixOrPlaylistLockupInfoItemExtractor(obj) }
                        }
                        key in ChannelRenderers -> commit(key) { YoutubeChannelInfoItemExtractor(obj) }
                        key in PlaylistRenderers -> commit(key) { YoutubePlaylistInfoItemExtractor(obj) }
                        // The first one in page order continues the item list; later ones load other sections.
                        key in ContinuationRenderers -> if (continuation == null) continuation = continuationToken(obj)
                        else -> walk(obj)
                    }
                }
            }
        }

        ContentRoots.forEach { walk(response[it]) }
        return FeedPage(collector.items, continuation, resume)
    }

    // Older renderers: the tap resumes through the watch endpoint, the thumbnail has a resume overlay.
    private fun rendererMark(renderer: JsonObject) = ResumeMark(
        startSeconds = renderer.getObject("navigationEndpoint").getObject("watchEndpoint").getInt("startTimeSeconds", 0),
        percent = findFirst(renderer["thumbnailOverlays"], "thumbnailOverlayResumePlaybackRenderer")?.getInt("percentDurationWatched", 0) ?: 0,
    )

    // Lockups: the same two, under the tap command and the thumbnail's progress bar view model.
    private fun lockupMark(lockup: JsonObject) = ResumeMark(
        startSeconds = findFirst(lockup["rendererContext"], "watchEndpoint")?.getInt("startTimeSeconds", 0) ?: 0,
        percent = findFirst(lockup["contentImage"], "thumbnailOverlayProgressBarViewModel")?.getInt("startPercent", 0) ?: 0,
    )

    /** The title YouTube gives a playlist page (`VL…`), or null. */
    fun playlistTitle(response: JsonObject): String? =
        response.getObject("metadata").getObject("playlistMetadataRenderer").getString("title")
            ?: findFirst(response["header"], "pageHeaderRenderer")?.getString("pageTitle")

    /** Who the cookies act as, from `account/account_menu`. */
    fun activeAccount(response: JsonObject): Identity? {
        val header = findFirst(response, "activeAccountHeaderRenderer") ?: return null
        val name = getTextFromObject(header.getObject("accountName")) ?: return null
        return Identity(
            name = name,
            byline = getTextFromObject(header.getObject("channelHandle")),
            avatar = largestThumbnail(header.getObject("accountPhoto")),
            authUser = 0,
            pageId = null,
            selected = true,
        )
    }

    /**
     * Every identity of the browser session, from `getAccountSwitcherEndpoint`: each Google account and
     * its brand channels. A brand channel is reached by its `pageId`, and only together with the
     * `authuser` of the account that owns it, which YouTube gives only inside the sign-in URL.
     */
    fun identities(response: JsonObject): List<Identity> = findAll(response, "accountItem").mapNotNull { item ->
        val name = getTextFromObject(item.getObject("accountName")) ?: return@mapNotNull null
        val tokens = item.getObject("serviceEndpoint").getObject("selectActiveIdentityEndpoint").getArray("supportedTokens")
            .filterIsInstance<JsonObject>()
        val pageId = tokens.firstNotNullOfOrNull { it.getObject("pageIdToken").getString("pageId") }
        val signIn = tokens.firstNotNullOfOrNull { it.getObject("accountSigninToken").getString("signinUrl") }
        Identity(
            name = name,
            byline = getTextFromObject(item.getObject("channelHandle")) ?: getTextFromObject(item.getObject("accountByline")),
            avatar = largestThumbnail(item.getObject("accountPhoto")),
            authUser = signIn?.let { AuthUserParam.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0,
            pageId = pageId,
            selected = item.getBoolean("isSelected", false),
        )
    }

    private val AuthUserParam = Regex("[?&]authuser=(\\d+)")

    // continuationItemViewModel nests one continuationCommand in another; only the inner one has the token.
    private fun continuationToken(renderer: JsonObject): String? =
        findFirst(renderer, "continuationCommand") { it.isString("token") }?.getString("token")

    private fun largestThumbnail(photo: JsonObject): String? =
        photo.getArray("thumbnails").filterIsInstance<JsonObject>().lastOrNull()?.getString("url")?.let { if (it.startsWith("//")) "https:$it" else it }

    /** The first object under [key] anywhere below [node] that passes [accept], depth first in document order. */
    fun findFirst(node: Any?, key: String, accept: (JsonObject) -> Boolean = { true }): JsonObject? {
        when (node) {
            is JsonObject -> {
                (node[key] as? JsonObject)?.takeIf(accept)?.let { return it }
                for (value in node.values) findFirst(value, key, accept)?.let { return it }
            }
            is JsonArray -> for (value in node) findFirst(value, key, accept)?.let { return it }
        }
        return null
    }

    fun findAll(node: Any?, key: String): List<JsonObject> = buildList {
        fun walk(n: Any?) {
            when (n) {
                is JsonObject -> for ((k, v) in n) if (k == key && v is JsonObject) add(v) else walk(v)
                is JsonArray -> n.forEach(::walk)
            }
        }
        walk(node)
    }
}
