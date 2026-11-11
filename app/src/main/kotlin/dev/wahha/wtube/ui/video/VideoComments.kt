package dev.wahha.wtube.ui.video

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Comment
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.errorDetail
import dev.wahha.wtube.ui.components.launchAction
import kotlinx.coroutines.CoroutineScope
import org.schabi.newpipe.extractor.Page
import kotlin.coroutines.cancellation.CancellationException

/**
 * The comments of one video: the list, its sorting, opened replies, likes given here and the account's
 * comment field. Through InnerTube, as the web page loads them: signed in, each comment comes with its
 * like and reply actions; either way the list can be sorted. [extractor] stands in when the watch page
 * gave no comments token ([firstToken]) or the anonymous request failed.
 */
class VideoComments(
    private val scope: CoroutineScope,
    private val firstToken: suspend () -> String?,
    private val extractor: suspend (Page?) -> PageOf<Comment>,
) {
    val list = PagedList(scope, Comment::id) { load(it) }

    /** Lets the account post a comment; set by the first page loaded through the account. */
    var box by mutableStateOf<String?>(null)
        private set

    /** Comments liked or unliked here, by id, over what the list said. */
    private val likes = mutableStateMapOf<String, Boolean>()

    /** Tokens that reload the comments as Top or Newest; empty when the list came from the extractor. */
    var sorts by mutableStateOf<List<String>>(emptyList())
        private set

    /** Index into [sorts]; 0 is YouTube's default, Top. */
    var sort by mutableIntStateOf(0)
        private set

    /** Replies of the opened threads, by comment id. */
    val replies = mutableStateMapOf<String, PagedList<Comment>>()

    private suspend fun load(page: Page?): PageOf<Comment> {
        if (page != null && !Feeds.isCursor(page)) return extractor(page)
        val token = if (page == null) sorts.getOrNull(sort) ?: firstToken() ?: return extractor(null) else null
        val result = try {
            Actions.comments(token, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (page != null || Account.active != null) throw e
            Log.w("Video", "InnerTube comments failed, extractor instead: ${errorDetail(e)}")
            ErrorLog.note("[video] InnerTube comments failed, extractor instead: ${errorDetail(e)}")
            return extractor(null)
        }
        if (page == null) {
            box = result.createParams
            if (result.sortTokens.isNotEmpty()) sorts = result.sortTokens
        }
        return PageOf(result.comments, result.continuation?.let(Feeds::cursor))
    }

    /** Reloads the comments as YouTube's sort [index] orders them (0 Top, 1 Newest). */
    fun sortBy(index: Int) {
        if (index == sort || index !in sorts.indices) return
        sort = index
        replies.clear()
        list.refresh()
    }

    /** Opens or closes the replies of [comment] under it. */
    fun toggleReplies(comment: Comment) {
        val first = comment.repliesPage ?: return
        if (replies.remove(comment.id) != null) return
        replies[comment.id] = PagedList(scope, Comment::id) { page ->
            val at = page ?: first
            if (Feeds.isCursor(at)) {
                Actions.comments(null, at).let { PageOf(it.comments, it.continuation?.let(Feeds::cursor)) }
            } else {
                extractor(at)
            }
        }.also { it.refresh() }
    }

    fun post(text: String) {
        val params = box ?: return
        launchAction(R.string.comment_posted) {
            Actions.comment(params, text)
            // YouTube shows the author's fresh comment first.
            list.refresh()
        }
    }

    fun reply(comment: Comment, text: String) {
        val params = comment.actions?.replyParams ?: return
        launchAction(R.string.reply_posted) { Actions.reply(params, text) }
    }

    fun isLiked(comment: Comment): Boolean = likes[comment.id] ?: comment.actions?.liked ?: false

    fun like(comment: Comment) {
        val actions = comment.actions ?: return
        val liked = isLiked(comment)
        val token = (if (liked) actions.unlike else actions.like) ?: return
        likes[comment.id] = !liked
        launchAction(onFailure = { likes[comment.id] = liked }) { Actions.commentAction(token) }
    }
}
