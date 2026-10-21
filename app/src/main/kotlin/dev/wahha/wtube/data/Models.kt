package dev.wahha.wtube.data

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.Frameset

/** A row in any list: a video, a channel or a playlist. */
sealed interface Item {
    val url: String
}

@Serializable
data class VideoItem(
    val id: String,
    val title: String,
    val thumbnail: String?,
    val channelName: String?,
    val channelUrl: String?,
    val channelAvatar: String?,
    /** Seconds; 0 when unknown or live. */
    val duration: Long,
    /** -1 when unknown. */
    val views: Long,
    /** YouTube's own date text, shown only when [uploadedAt] could not be parsed. */
    val uploaded: String?,
    val isLive: Boolean,
    /** Epoch millis, approximate for "N days ago" sources. */
    val uploadedAt: Long? = null,
) : Item {
    override val url: String get() = watchUrl(id)
}

data class ChannelItem(
    override val url: String,
    val name: String,
    val avatar: String?,
    val subscribers: Long,
    val verified: Boolean,
) : Item

data class PlaylistItem(
    override val url: String,
    val name: String,
    val thumbnail: String?,
    val uploader: String?,
    /** -1 when unknown. */
    val count: Long,
) : Item

/** One page of a list plus the cursor for the next one (null when this was the last). */
class PageOf<T>(val items: List<T>, val next: Page?)

typealias ItemsPage = PageOf<Item>

class VideoDetails(
    val item: VideoItem,
    /** HTML from YouTube. */
    val description: String,
    /** -1 when hidden. */
    val likes: Long,
    /** -1 when unknown. */
    val subscribers: Long,
    /** Epoch millis, null when YouTube gave only a textual date. */
    val uploadedAt: Long?,
    val related: List<Item>,
    val chapters: List<Chapter>,
    /** Storyboard sheets for previews while seeking, smallest frames first; empty for live streams. */
    val storyboard: List<Frameset>,
    /** Caption tracks, one per language and kind: what the transcript is read from. */
    val captions: List<CaptionTrack> = emptyList(),
)

class Chapter(val title: String, val startSeconds: Int, val thumbnail: String?)

class Comment(
    val id: String,
    val author: String,
    val authorUrl: String?,
    val avatar: String?,
    /** HTML from YouTube. */
    val html: String,
    val likes: Int,
    val postedAt: Long?,
    val posted: String?,
    val replies: Int,
    val pinned: Boolean,
    val hearted: Boolean,
    /** What the signed-in account can do with it; null in lists loaded without an account. */
    val actions: CommentActions? = null,
    /** The first page of its replies; null when it has none. */
    val repliesPage: Page? = null,
)

/** Tokens YouTube hands a signed-in account for one comment; each null when it offers no such action. */
class CommentActions(
    val liked: Boolean,
    val like: String?,
    val unlike: String?,
    val replyParams: String?,
)

class ChannelDetails(
    val url: String,
    /** The `UC…` id, whatever form [url] has. */
    val id: String,
    val name: String,
    val avatar: String?,
    val banner: String?,
    val subscribers: Long,
    val description: String,
    val verified: Boolean,
    val tabs: List<ChannelTab>,
)

/** A channel tab we show; [kind] is one of the extractor's `ChannelTabs` names. */
class ChannelTab(val kind: String, val handler: ListLinkHandler)

class PlaylistDetails(
    val url: String,
    val name: String,
    val thumbnail: String?,
    val uploader: String?,
    val count: Long,
    val firstPage: ItemsPage,
)

sealed interface LinkTarget {
    data class Video(val id: String) : LinkTarget
    data class Playlist(val url: String) : LinkTarget
    data class Channel(val url: String) : LinkTarget
    /** A channel's live page (`/@name/live`): its current stream, else the channel; see [YouTube.liveVideoId]. */
    data class Live(val url: String) : LinkTarget
}

fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
