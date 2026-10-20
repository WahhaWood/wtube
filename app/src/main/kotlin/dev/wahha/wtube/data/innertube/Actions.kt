package dev.wahha.wtube.data.innertube

import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.account.SessionExpiredException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList

/**
 * What the signed-in account does on YouTube: rate, subscribe, save to playlists, comment, and report
 * what it watched. The writes go out the way the web client sends them; each throws
 * [SessionExpiredException] when there is no session or YouTube stopped accepting it.
 */
object Actions {
    const val WATCH_LATER = Feeds.WATCH_LATER

    private fun session() = Account.active ?: throw SessionExpiredException()

    /** The account's view of [videoId]: its rating, subscription and comments token; signed out, only the token. */
    suspend fun watch(videoId: String, session: Session? = Account.active): Watch {
        val watch = WatchParser.watch(InnerTube.next(session, videoId = videoId))
        return if (session == null) Watch(state = null, commentsToken = watch.commentsToken, heatmap = watch.heatmap) else watch
    }

    suspend fun rate(videoId: String, status: LikeStatus) {
        val endpoint = when (status) {
            LikeStatus.LIKE -> "like/like"
            LikeStatus.DISLIKE -> "like/dislike"
            LikeStatus.INDIFFERENT -> "like/removelike"
        }
        InnerTube.post(session(), endpoint) { `object`("target").value("videoId", videoId).end() }
    }

    /** Whether the account follows channel [channelId] (`UC…`); null when signed out or YouTube did not say. */
    suspend fun subscribed(channelId: String, session: Session? = Account.active): Boolean? =
        session?.let { WatchParser.subscribed(InnerTube.browse(it, browseId = channelId)) }

    suspend fun subscribe(channelId: String, subscribe: Boolean) {
        InnerTube.post(session(), if (subscribe) "subscription/subscribe" else "subscription/unsubscribe") {
            array("channelIds").value(channelId).end()
        }
    }

    /**
     * The account's playlists and whether [videoId] is in each. Should YouTube stop answering in the
     * known shape, the Library's playlist list stands in, with nothing marked as containing the video.
     */
    suspend fun playlistOptions(videoId: String, session: Session = session()): List<PlaylistOption> {
        val response = InnerTube.post(session, "playlist/get_add_to_playlist") { array("videoIds").value(videoId).end() }
        WatchParser.playlistOptions(response).takeIf { it.isNotEmpty() }?.let { return it }
        return listOf(PlaylistOption(WATCH_LATER, "", contains = false, privacy = "PRIVATE")) +
            Feeds.playlists().items.filterIsInstance<PlaylistItem>().mapNotNull { playlist ->
                YouTube.playlistIdOf(playlist.url)?.let { PlaylistOption(it, playlist.name, contains = false, privacy = null) }
            }
    }

    suspend fun setInPlaylist(playlistId: String, videoId: String, add: Boolean) {
        val response = InnerTube.post(session(), "browse/edit_playlist") {
            value("playlistId", playlistId)
            array("actions").`object`()
            if (add) value("action", "ACTION_ADD_VIDEO").value("addedVideoId", videoId)
            else value("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID").value("removedVideoId", videoId)
            end().end()
        }
        val status = response.getString("status")
        if (status != null && status != "STATUS_SUCCEEDED") throw ActionRejectedException("edit_playlist: $status")
    }

    /** A new playlist with [videoId] in it; [privacy] is PRIVATE, UNLISTED or PUBLIC. */
    suspend fun createPlaylist(title: String, privacy: String, videoId: String) {
        val response = InnerTube.post(session(), "playlist/create") {
            value("title", title)
            value("privacyStatus", privacy)
            array("videoIds").value(videoId).end()
        }
        if (response.getString("playlistId") == null) throw ActionRejectedException("playlist/create: no playlist")
    }

    /**
     * The comments page [token] (from [Watch.commentsToken] or a sort token) or [page] points to; a thread's
     * replies too. Signed out it is the anonymous page, without actions.
     */
    suspend fun comments(token: String?, page: Page?, session: Session? = Account.active): CommentsPage {
        val response = InnerTube.next(session, continuation = page?.id ?: token)
        return WatchParser.comments(response, ServiceList.YouTube.getTimeAgoParser(NewPipe.getPreferredLocalization()))
    }

    suspend fun comment(createParams: String, text: String) {
        InnerTube.post(session(), "comment/create_comment") {
            value("createCommentParams", createParams)
            value("commentText", text)
        }
    }

    suspend fun reply(replyParams: String, text: String) {
        InnerTube.post(session(), "comment/create_comment_reply") {
            value("createReplyParams", replyParams)
            value("commentText", text)
        }
    }

    /** Likes or unlikes a comment with the token [CommentActions][dev.wahha.wtube.data.CommentActions] holds. */
    suspend fun commentAction(action: String) {
        InnerTube.post(session(), "comment/perform_comment_action") { array("actions").value(action).end() }
    }

    /** The stats URLs to report watching [videoId] with; null when signed out or the page had none. */
    suspend fun tracking(videoId: String, session: Session? = Account.active): Tracking? =
        session?.let { WatchParser.tracking(InnerTube.watchPage(it, videoId)) }

    suspend fun ping(url: String) = InnerTube.ping(session(), url)
}

/** YouTube answered an action but did not do it (a video already in Watch later, a refused playlist). */
class ActionRejectedException(message: String) : Exception(message)
