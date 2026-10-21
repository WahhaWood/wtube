package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.Comment
import dev.wahha.wtube.data.CommentActions
import org.schabi.newpipe.extractor.localization.TimeAgoParser
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getTextFromObject
import org.schabi.newpipe.extractor.utils.Utils

enum class LikeStatus { LIKE, DISLIKE, INDIFFERENT }

/** What the signed-in account has done about one video: its rating and whether it follows the channel. */
data class VideoState(val like: LikeStatus, val channelId: String?, val subscribed: Boolean?)

/**
 * The watch page as the account sees it: [state] (null on the anonymous page), the token of the first
 * comments page (null when off) and the "Most replayed" graph: intensities 0..1 of equal slices of the
 * video, in order (empty when YouTube has none, as for new or short videos); [chat] for live streams
 * and past ones with a chat replay.
 */
class Watch(val state: VideoState?, val commentsToken: String?, val heatmap: List<Float> = emptyList(), val chat: ChatSource? = null)

/** One of the account's playlists in "Save to"; [contains] says whether the video is in it already. */
data class PlaylistOption(val id: String, val title: String, val contains: Boolean, val privacy: String?)

/** The web player's stats URLs for one video; pinging them with the cookies puts the view in the account's history. */
class Tracking(val playbackUrl: String, val watchtimeUrl: String)

/**
 * A page of comments as the account sees them; [createParams] (first page only) lets it post one, and
 * [sortTokens] (first page only) reload the list as Top or Newest, in YouTube's menu order.
 */
class CommentsPage(val comments: List<Comment>, val continuation: String?, val createParams: String?, val sortTokens: List<String> = emptyList())

/**
 * Reads the signed-in watch page, comments and "Save to" responses. Like [FeedParser], it looks objects
 * up by key anywhere below a known root rather than by full path: the web client moves them around.
 */
object WatchParser {
    fun watch(next: JsonObject): Watch {
        val updates = next["frameworkUpdates"]
        val contents = next["contents"]
        val like = (FeedParser.findFirst(updates, "likeStatusEntity") ?: FeedParser.findFirst(contents, "likeStatusEntity"))?.getString("likeStatus")
        val secondary = FeedParser.findFirst(contents, "videoSecondaryInfoRenderer")
        val button = FeedParser.findFirst(secondary, "subscribeButtonRenderer")
        val channelId = button?.getString("channelId")
            ?: FeedParser.findFirst(secondary, "subscribeEndpoint")?.getArray("channelIds")?.firstOrNull() as? String
            ?: FeedParser.findFirst(FeedParser.findFirst(secondary, "videoOwnerRenderer"), "browseEndpoint")?.getString("browseId")
        val subscribed = button?.takeIf { it.isBoolean("subscribed") }?.getBoolean("subscribed") ?: subscribedEntity(updates)
        val comments = FeedParser.findFirst(contents, "itemSectionRenderer") { it.getString("sectionIdentifier") == "comment-item-section" }
        return Watch(
            state = VideoState(LikeStatus.entries.firstOrNull { it.name == like } ?: LikeStatus.INDIFFERENT, channelId, subscribed),
            commentsToken = comments?.let(::continuationToken),
            heatmap = heatmap(updates),
            chat = chat(contents),
        )
    }

    // The chat sits in the conversation bar; without a reload token it is off.
    private fun chat(contents: Any?): ChatSource? {
        val renderer = FeedParser.findFirst(contents, "liveChatRenderer") ?: return null
        val token = FeedParser.findFirst(renderer["continuations"], "reloadContinuationData")?.getString("continuation") ?: return null
        return ChatSource(token, renderer.getBoolean("isReplay", false))
    }

    // The web player draws it from a markers entity: equal slices, each with its normalized replay score.
    private fun heatmap(updates: Any?): List<Float> {
        val markers = FeedParser.findFirst(updates, "markersList") { it.getString("markerType") == "MARKER_TYPE_HEATMAP" }
            ?.getArray("markers")?.filterIsInstance<JsonObject>().orEmpty()
        return markers.sortedBy { it.getString("startMillis")?.toLongOrNull() ?: 0 }
            .map { it.getDouble("intensityScoreNormalized").toFloat().coerceIn(0f, 1f) }
    }

    /** Whether the account follows the channel of a channel page (`browse` with its `UC…` id); null when unknown. */
    fun subscribed(channelPage: JsonObject): Boolean? =
        subscribedEntity(channelPage["frameworkUpdates"])
            ?: FeedParser.findFirst(channelPage["header"], "subscribeButtonRenderer")?.takeIf { it.isBoolean("subscribed") }?.getBoolean("subscribed")

    private fun subscribedEntity(updates: Any?): Boolean? =
        FeedParser.findFirst(updates, "subscriptionStateEntity") { it.isBoolean("subscribed") }?.getBoolean("subscribed")

    /** The account's playlists from `playlist/get_add_to_playlist`, Watch later first as YouTube orders them. */
    fun playlistOptions(response: JsonObject): List<PlaylistOption> =
        FeedParser.findAll(response, "playlistAddToOptionRenderer").mapNotNull { option ->
            PlaylistOption(
                id = option.getString("playlistId") ?: return@mapNotNull null,
                title = getTextFromObject(option.getObject("title")).orEmpty(),
                contains = option.getString("containsSelectedVideos") == "ALL",
                privacy = option.getString("privacy"),
            )
        }

    private val PlaybackUrl = Regex(""""videostatsPlaybackUrl":\{"baseUrl":"([^"]+)"""")
    private val WatchtimeUrl = Regex(""""videostatsWatchtimeUrl":\{"baseUrl":"([^"]+)"""")
    private val JsEscape = Regex("""\\u([0-9a-fA-F]{4})|\\/""")

    /** The stats URLs inside the watch page's player response, or null when the page has none (not playable). */
    fun tracking(html: String): Tracking? {
        fun url(regex: Regex) = regex.find(html)?.groupValues?.get(1)?.replace(JsEscape) { m ->
            m.groupValues[1].takeIf { it.isNotEmpty() }?.toInt(16)?.toChar()?.toString() ?: "/"
        }
        return Tracking(url(PlaybackUrl) ?: return null, url(WatchtimeUrl) ?: return null)
    }

    /**
     * A comments page (`next` with a comments continuation). A comment is a view model of entity keys; the
     * text, author and counts sit in the comment entity, the account's like in the toolbar state entity, and
     * the action tokens in the toolbar surface entity, all in `frameworkUpdates`.
     */
    fun comments(response: JsonObject, timeAgoParser: TimeAgoParser?): CommentsPage {
        val entities = response.getObject("frameworkUpdates").getObject("entityBatchUpdate").getArray("mutations")
            .filterIsInstance<JsonObject>()
            .mapNotNull { mutation -> mutation.getString("entityKey")?.let { it to mutation.getObject("payload") } }
            .toMap()
        val comments = mutableListOf<Comment>()
        var continuation: String? = null
        var createParams: String? = null
        var sortTokens = emptyList<String>()
        response.getArray("onResponseReceivedEndpoints").filterIsInstance<JsonObject>().forEach { endpoint ->
            val command = endpoint.getObject("reloadContinuationItemsCommand").takeIf { it.has("continuationItems") }
                ?: endpoint.getObject("appendContinuationItemsAction")
            command.getArray("continuationItems").filterIsInstance<JsonObject>().forEach { item ->
                item.getObject("commentsHeaderRenderer").takeIf { it.isNotEmpty() }?.let { header ->
                    createParams = FeedParser.findFirst(header, "createCommentEndpoint")?.getString("createCommentParams")
                    sortTokens = FeedParser.findFirst(header, "sortFilterSubMenuRenderer")?.getArray("subMenuItems")
                        ?.filterIsInstance<JsonObject>()?.mapNotNull { continuationToken(it) }.orEmpty()
                }
                val thread = item.getObject("commentThreadRenderer")
                // A thread holds its comment one level deeper; a page of replies lists the comments themselves.
                (thread.getObject("commentViewModel").getObject("commentViewModel").takeIf { it.isNotEmpty() }
                    ?: item.getObject("commentViewModel").takeIf { it.isNotEmpty() })
                    ?.let { comment(it, entities, timeAgoParser, thread.getObject("replies").takeIf { r -> r.isNotEmpty() }?.let(::continuationToken)) }
                    ?.let(comments::add)
                // Inside a thread every continuation loads its replies; only a top-level one is the next page.
                item.getObject("continuationItemRenderer").takeIf { it.isNotEmpty() }?.let { continuation = continuationToken(it) }
            }
        }
        return CommentsPage(comments, continuation, createParams, sortTokens)
    }

    private fun comment(viewModel: JsonObject, entities: Map<String, JsonObject>, timeAgoParser: TimeAgoParser?, repliesToken: String?): Comment? {
        val entity = entities[viewModel.getString("commentKey")]?.getObject("commentEntityPayload") ?: return null
        val properties = entity.getObject("properties")
        val author = entity.getObject("author")
        val toolbar = entity.getObject("toolbar")
        val state = entities[viewModel.getString("toolbarStateKey")]?.getObject("engagementToolbarStateEntityPayload")
        val surface = entities[viewModel.getString("toolbarSurfaceKey")]?.getObject("engagementToolbarSurfaceEntityPayload")
        val published = properties.getString("publishedTime")?.removeSuffix(" (edited)")
        val actions = CommentActions(
            liked = state?.getString("likeState") == "TOOLBAR_LIKE_STATE_LIKED",
            like = commentAction(surface?.get("likeCommand")),
            unlike = commentAction(surface?.get("unlikeCommand")),
            replyParams = FeedParser.findFirst(surface?.get("replyCommand"), "createCommentReplyEndpoint")?.getString("createReplyParams"),
        )
        return Comment(
            id = viewModel.getString("commentId") ?: properties.getString("commentId") ?: return null,
            author = author.getString("displayName").orEmpty(),
            authorUrl = author.getString("channelId")?.let { "https://www.youtube.com/channel/$it" },
            avatar = author.getString("avatarThumbnailUrl"),
            html = html(properties.getObject("content")),
            likes = count(toolbar.getString("likeCountNotliked")),
            postedAt = published?.let { runCatching { timeAgoParser?.parse(it)?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() },
            posted = published,
            replies = count(toolbar.getString("replyCount")),
            pinned = viewModel.has("pinnedText"),
            hearted = state?.getString("heartState") == "TOOLBAR_HEART_STATE_HEARTED",
            actions = actions.takeIf { it.like != null || it.unlike != null || it.replyParams != null },
            repliesPage = repliesToken?.let(Feeds::cursor),
        )
    }

    private fun commentAction(command: Any?): String? = FeedParser.findFirst(command, "performCommentActionEndpoint")?.getString("action")

    // "104K" or "1.2M" as YouTube prints counts; empty when there are none.
    private fun count(text: String?): Int =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { Utils.mixedNumberWordToLong(it).toInt() }.getOrNull() } ?: 0

    /** The comment text as HTML, its links (timestamps, channels, URLs) from the command runs. */
    private fun html(content: JsonObject): String {
        val text = content.getString("content").orEmpty()
        val links = content.getArray("commandRuns").filterIsInstance<JsonObject>().mapNotNull { run ->
            val start = run.getInt("startIndex", -1)
            val end = start + run.getInt("length", 0)
            val url = FeedParser.findFirst(run, "webCommandMetadata")?.getString("url")?.let(::absoluteUrl)
            if (start < 0 || end > text.length || end <= start || url == null) null else Triple(start, end, url)
        }.sortedBy { it.first }
        return buildString {
            var at = 0
            for ((start, end, url) in links) {
                if (start < at) continue
                append(escape(text.substring(at, start)))
                append("<a href=\"").append(escape(url)).append("\">").append(escape(text.substring(start, end))).append("</a>")
                at = end
            }
            append(escape(text.substring(at)))
        }
    }

    // Outside links go through youtube.com/redirect with the real one in `q`.
    private fun absoluteUrl(url: String): String {
        val full = if (url.startsWith("/")) "https://www.youtube.com$url" else url
        if (!full.startsWith("https://www.youtube.com/redirect")) return full
        return Regex("[?&]q=([^&]+)").find(full)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: full
    }

    private fun escape(text: String) =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\n", "<br>")

    private fun continuationToken(node: JsonObject): String? =
        FeedParser.findFirst(node, "continuationCommand") { it.isString("token") }?.getString("token")
}
