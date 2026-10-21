package dev.wahha.wtube.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.ui.icon.Article
import dev.wahha.wtube.ui.icon.DownloadDone
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.downloads.DownloadSheet
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.DeArrow
import dev.wahha.wtube.data.Dislikes
import androidx.compose.runtime.mutableLongStateOf
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.ui.components.errorDetail
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.ui.icon.PlaylistPlay
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.R
import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.Comment
import dev.wahha.wtube.data.VideoDetails
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.shareVideo
import dev.wahha.wtube.ui.components.subscribersText
import dev.wahha.wtube.ui.components.viewsText
import dev.wahha.wtube.ui.glass.CapsuleShape
import dev.wahha.wtube.ui.icon.Share
import dev.wahha.wtube.ui.icon.ThumbUp
import dev.wahha.wtube.ui.icon.WIcons
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.data.innertube.LikeStatus
import dev.wahha.wtube.data.innertube.VideoState
import dev.wahha.wtube.data.innertube.Watch
import dev.wahha.wtube.ui.components.SaveSheet
import dev.wahha.wtube.ui.components.SubscribeButton
import dev.wahha.wtube.ui.components.launchAction
import dev.wahha.wtube.ui.icon.PlaylistAdd
import dev.wahha.wtube.ui.icon.ThumbDown
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import org.schabi.newpipe.extractor.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

/**
 * The video on the player sheet. One per activity rather than one per video: the sheet collapses and
 * expands without reloading, and opening another video replaces this one.
 */
class VideoViewModel : ViewModel() {
    var id by mutableStateOf<String?>(null)
        private set
    var details by mutableStateOf<VideoDetails?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var comments by mutableStateOf<PagedList<Comment>?>(null)
        private set

    /** The signed-in account's rating and subscription; null when signed out or YouTube did not answer. */
    var state by mutableStateOf<VideoState?>(null)
        private set

    /** Lets the account post a comment; set by the first comments page loaded through the account. */
    var commentBox by mutableStateOf<String?>(null)
        private set

    /** Comments liked or unliked here, by id, over what the list said. */
    val commentLikes = mutableStateMapOf<String, Boolean>()

    /** Tokens that reload the comments as Top or Newest; empty when the list came from the extractor. */
    var commentSorts by mutableStateOf<List<String>>(emptyList())
        private set

    /** Index into [commentSorts]; 0 is YouTube's default, Top. */
    var commentSort by mutableIntStateOf(0)
        private set

    /** YouTube's "Most replayed" graph: intensities 0..1 of equal slices of the video; empty when it has none. */
    var heatmap by mutableStateOf<List<Float>>(emptyList())
        private set

    /** The live chat or chat replay; null when the video has none. */
    var chat by mutableStateOf<ChatState?>(null)
        private set

    /** The chat takes the page under the video instead of the details. */
    var chatOpen by mutableStateOf(false)

    /** DeArrow's title for the video, null when off, unknown or the same as the real one. */
    var betterTitle by mutableStateOf<String?>(null)
        private set

    /** Return YouTube Dislike's estimate; -1 when off or unknown. */
    var dislikes by mutableLongStateOf(-1L)
        private set

    /** Replies of the threads opened in the comments sheet, by comment id. */
    val replies = mutableStateMapOf<String, PagedList<Comment>>()

    /** Set by [open] until the sheet hands the video to the player, which needs [details] cached first. */
    var playPending by mutableStateOf(false)
        private set

    /** Videos opened before this one in this session, newest last: what "previous" goes back to. */
    private val previous = mutableStateListOf<String>()
    val hasPrevious: Boolean get() = previous.isNotEmpty()

    private var job: Job? = null
    private var watch: Deferred<Watch?>? = null
    // The rating YouTube reported, to move the like count by what changed here.
    private var ratedBefore = LikeStatus.INDIFFERENT

    /** Shows [id]; [play] false adopts a video the player is already playing, without restarting it. */
    fun open(id: String, play: Boolean = true) {
        playPending = play
        if (id == this.id) {
            if (error != null) load()
            return
        }
        this.id?.let { current ->
            previous.remove(current)
            previous.add(current)
            if (previous.size > MAX_PREVIOUS) previous.removeAt(0)
        }
        this.id = id
        details = null
        state = null
        commentBox = null
        commentLikes.clear()
        commentSorts = emptyList()
        commentSort = 0
        replies.clear()
        dislikes = -1
        betterTitle = null
        heatmap = emptyList()
        chat = null
        chatOpen = false
        comments = PagedList(viewModelScope, Comment::id) { loadComments(id, it) }
        load()
    }

    fun load() {
        val id = id ?: return
        error = null
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val watch = async { runCatching { Actions.watch(id) }.getOrNull() }
                if (Settings.current.value.showDislikes) {
                    launch { runCatching { Dislikes.of(id) }.onSuccess { if (this@VideoViewModel.id == id) dislikes = it } }
                }
                if (Settings.current.value.deArrow) {
                    launch { runCatching { DeArrow.title(id) }.onSuccess { if (this@VideoViewModel.id == id) betterTitle = it } }
                }
                this@VideoViewModel.watch = watch
                val details = try {
                    YouTube.video(id)
                } catch (e: Exception) {
                    // Offline, a downloaded video still has its page: what was saved with the download.
                    (Downloads.finished(id, audioOnly = false) ?: Downloads.finished(id, audioOnly = true))?.let { offlineDetails(it.video) } ?: throw e
                }
                this@VideoViewModel.details = details
                comments?.refresh()
                val watched = watch.await()
                heatmap = watched?.heatmap.orEmpty()
                if (chat == null) chat = watched?.chat?.let(::ChatState)
                watched?.state?.let { s ->
                    ratedBefore = s.like
                    state = if (s.channelId != null) s else s.copy(channelId = details.item.channelUrl?.substringAfter("/channel/", "")?.ifEmpty { null })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
    }

    /**
     * Through InnerTube, as the web page loads them: signed in, each comment comes with its like and reply
     * actions; either way the list can be sorted. The extractor's list stands in when the watch page gave
     * no comments token or the anonymous request failed.
     */
    private suspend fun loadComments(id: String, page: Page?): PageOf<Comment> {
        if (page != null && !Feeds.isCursor(page)) return YouTube.comments(id, page)
        val token = if (page == null) commentSorts.getOrNull(commentSort) ?: watch?.await()?.commentsToken ?: return YouTube.comments(id, null) else null
        val result = try {
            Actions.comments(token, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (page != null || Account.active != null) throw e
            Log.w("Video", "InnerTube comments failed, extractor instead: ${errorDetail(e)}")
            return YouTube.comments(id, null)
        }
        if (page == null) {
            commentBox = result.createParams
            if (result.sortTokens.isNotEmpty()) commentSorts = result.sortTokens
        }
        return PageOf(result.comments, result.continuation?.let(Feeds::cursor))
    }

    /** Reloads the comments as YouTube's sort [index] orders them (0 Top, 1 Newest). */
    fun sortComments(index: Int) {
        if (index == commentSort || index !in commentSorts.indices) return
        commentSort = index
        replies.clear()
        comments?.refresh()
    }

    /** Opens or closes the replies of [comment] under it. */
    fun toggleReplies(comment: Comment) {
        val first = comment.repliesPage ?: return
        if (replies.remove(comment.id) != null) return
        val id = id ?: return
        replies[comment.id] = PagedList(viewModelScope, Comment::id) { page ->
            val at = page ?: first
            if (Feeds.isCursor(at)) {
                Actions.comments(null, at).let { PageOf(it.comments, it.continuation?.let(Feeds::cursor)) }
            } else {
                YouTube.comments(id, at)
            }
        }.also { it.refresh() }
    }

    private fun offlineDetails(video: VideoItem) = VideoDetails(
        item = video,
        description = "",
        likes = -1,
        subscribers = -1,
        uploadedAt = video.uploadedAt,
        related = emptyList(),
        chapters = emptyList(),
        storyboard = emptyList(),
    )

    /** The like count with this screen's rating applied. */
    val likes: Long
        get() {
            val likes = details?.likes ?: return -1
            if (likes < 0) return likes
            val now = state?.like ?: return likes
            return likes + (if (now == LikeStatus.LIKE) 1 else 0) - (if (ratedBefore == LikeStatus.LIKE) 1 else 0)
        }

    /** Return YouTube Dislike's count with this screen's rating applied, the way [likes] does. */
    val dislikesShown: Long
        get() {
            if (dislikes < 0) return dislikes
            val now = state?.like ?: return dislikes
            return dislikes + (if (now == LikeStatus.DISLIKE) 1 else 0) - (if (ratedBefore == LikeStatus.DISLIKE) 1 else 0)
        }

    /** Likes or dislikes; the same again takes the rating back. */
    fun rate(status: LikeStatus) {
        val id = id ?: return
        val before = state ?: return
        val rating = if (before.like == status) LikeStatus.INDIFFERENT else status
        state = before.copy(like = rating)
        launchAction(onFailure = { if (this.id == id) state = state?.copy(like = before.like) }) { Actions.rate(id, rating) }
    }

    fun subscribe(subscribe: Boolean) {
        val id = id ?: return
        val before = state ?: return
        val channel = before.channelId ?: return
        state = before.copy(subscribed = subscribe)
        launchAction(onFailure = { if (this.id == id) state = state?.copy(subscribed = before.subscribed) }) { Actions.subscribe(channel, subscribe) }
    }

    fun postComment(text: String) {
        val params = commentBox ?: return
        val comments = comments
        launchAction(R.string.comment_posted) {
            Actions.comment(params, text)
            // YouTube shows the author's fresh comment first.
            comments?.refresh()
        }
    }

    fun reply(comment: Comment, text: String) {
        val params = comment.actions?.replyParams ?: return
        launchAction(R.string.reply_posted) { Actions.reply(params, text) }
    }

    fun isLiked(comment: Comment): Boolean = commentLikes[comment.id] ?: comment.actions?.liked ?: false

    fun likeComment(comment: Comment) {
        val actions = comment.actions ?: return
        val liked = isLiked(comment)
        val token = (if (liked) actions.unlike else actions.like) ?: return
        commentLikes[comment.id] = !liked
        launchAction(onFailure = { commentLikes[comment.id] = liked }) { Actions.commentAction(token) }
    }

    fun playStarted() {
        playPending = false
    }

    val hasNext: Boolean
        get() = PlayQueue.items.value.isNotEmpty() || nextCandidates().isNotEmpty()

    // Related videos "next" and autoplay may pick: not live, not this one, not hidden.
    private fun nextCandidates(): List<VideoItem> {
        val hidden = Hidden.items.value
        return details?.related.orEmpty().filterIsInstance<VideoItem>().filter { !it.isLive && it.id != id && hidden.allows(it) }
    }

    /** The video "next" plays: the queue's first, else the first related one not watched yet, as autoplay picks. */
    fun playNext() {
        val next = PlayQueue.pop() ?: nextCandidates().let { related ->
            val watched = History.entries.value.mapTo(HashSet()) { it.video.id }
            related.firstOrNull { it.id !in watched } ?: related.firstOrNull()
        }
        next?.let { open(it.id) }
    }

    /** Back to the video opened before this one, without remembering this one as its predecessor. */
    fun playPrevious() {
        val back = previous.removeLastOrNull() ?: return
        val current = id
        open(back)
        if (current != null) previous.remove(current)
    }

    fun close() {
        job?.cancel()
        id = null
        details = null
        error = null
        comments = null
        state = null
        commentBox = null
        commentSorts = emptyList()
        replies.clear()
        heatmap = emptyList()
        playPending = false
        previous.clear()
    }
}

private const val MAX_PREVIOUS = 50

/** Everything under the player: title, channel, actions, description, comments and related videos. */
@Composable
internal fun VideoInfo(vm: VideoViewModel, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val details = vm.details
    val comments = vm.comments
    val error = vm.error
    val chat = vm.chat
    Box(modifier) {
        when {
            chat != null && vm.chatOpen -> {
                BackHandler { vm.chatOpen = false }
                ChatContent(chat, onClose = { vm.chatOpen = false }, modifier = Modifier.fillMaxSize().navigationBarsPadding().imePadding())
            }
            details != null && comments != null -> Details(vm, details, comments, onSeek)
            error != null -> ErrorState(error, vm::load)
            else -> FeedSkeleton(Modifier.padding(top = 16.dp), compact = true)
        }
    }
}

/**
 * YouTube's ambient mode: the thumbnail, heavily blurred, glows behind the player and fades into the
 * page. Blur needs Android 12; below that the page simply stays plain.
 */
@Composable
internal fun AmbientGlow(thumbnail: String?) {
    if (thumbnail == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val background = MaterialTheme.colorScheme.background
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = top + maxWidth * 9f / 16f + 240.dp
        Box(Modifier.fillMaxWidth().height(height)) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize().blur(72.dp, BlurredEdgeTreatment.Rectangle).alpha(0.6f),
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0f),
                        0.45f to background.copy(alpha = 0.3f),
                        0.75f to background.copy(alpha = 0.8f),
                        1f to background,
                    ),
                ),
            )
        }
    }
}

@Composable
private fun Details(vm: VideoViewModel, details: VideoDetails, comments: PagedList<Comment>, onSeek: (Long) -> Unit) {
    val nav = LocalNavigator.current
    val video = details.item
    val state = vm.state
    val context = LocalContext.current
    val onLink = rememberLinkHandler(video.id, onSeek)
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var queueOpen by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var transcriptOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val downloads by Downloads.entries.collectAsStateWithLifecycle()
    val ours = downloads.filter { it.video.id == video.id }
    val queue by PlayQueue.items.collectAsStateWithLifecycle()
    val hidden by Hidden.items.collectAsStateWithLifecycle()
    if (commentsOpen) CommentsSheet(vm, comments, onLink, onDismiss = { commentsOpen = false })
    if (saving) SaveSheet(video.id, onDismiss = { saving = false })
    if (queueOpen) QueueSheet(onDismiss = { queueOpen = false })
    if (downloading) DownloadSheet(video, onDismiss = { downloading = false })
    if (transcriptOpen) TranscriptSheet(video.id, details.captions, onSeek, onDismiss = { transcriptOpen = false })
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vm.betterTitle?.takeIf { it != video.title } ?: video.title, style = MaterialTheme.typography.titleMedium)
                val date = details.uploadedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, AppLocale).format(Date(it)) } ?: video.uploaded
                Text(
                    listOfNotNull(viewsText(video.views), date).joinToString(stringResource(R.string.meta_separator)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item(key = "channel") {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = video.channelUrl != null) { video.channelUrl?.let(nav.openChannel) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Avatar(video.channelAvatar, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(video.channelName.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subscribersText(details.subscribers)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                // Signed in, the button subscribes and the row itself leads to the channel.
                val subscribed = state?.subscribed
                when {
                    subscribed != null && state.channelId != null -> SubscribeButton(subscribed, video.channelName.orEmpty(), onChange = {
                        haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        vm.subscribe(it)
                    })
                    video.channelUrl != null -> FilledTonalButton(onClick = { nav.openChannel(video.channelUrl) }) {
                        Text(stringResource(R.string.video_open_channel))
                    }
                }
            }
        }
        item(key = "actions") {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (queue.isNotEmpty()) {
                    ActionPill(WIcons.PlaylistPlay, stringResource(R.string.queue_count, queue.size)) { queueOpen = true }
                }
                if (state != null) {
                    RatingPill(state.like, vm.likes, vm.dislikesShown) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        vm.rate(it)
                    }
                    ActionPill(WIcons.PlaylistAdd, stringResource(R.string.video_save)) { saving = true }
                } else {
                    if (details.likes >= 0) ActionPill(WIcons.ThumbUp, formatCompact(details.likes), onClick = null)
                    if (vm.dislikes >= 0) ActionPill(WIcons.ThumbDown, formatCompact(vm.dislikes), onClick = null)
                }
                ActionPill(WIcons.Share, stringResource(R.string.video_share)) { shareVideo(context, video) }
                if (details.captions.isNotEmpty()) {
                    ActionPill(WIcons.Article, stringResource(R.string.video_transcript)) { transcriptOpen = true }
                }
                if (!video.isLive) {
                    if (ours.any { it.state == DownloadState.DONE }) {
                        ActionPill(WIcons.DownloadDone, stringResource(R.string.downloads_saved)) { nav.openDownloads() }
                    } else if (ours.isNotEmpty()) {
                        ActionPill(WIcons.Download, stringResource(R.string.downloads_title)) { nav.openDownloads() }
                    } else {
                        ActionPill(WIcons.Download, stringResource(R.string.downloads_action)) { downloading = true }
                    }
                }
            }
        }
        vm.chat?.let { chat ->
            item(key = "chat") { ChatPreview(chat, onOpen = { vm.chatOpen = true }) }
        }
        if (details.description.isNotBlank()) {
            item(key = "description") { Description(details.description, onLink) }
        }
        if (details.chapters.isNotEmpty()) {
            item(key = "chapters") { Chapters(details.chapters, onSeek) }
        }
        item(key = "comments") { CommentsPreview(comments, onOpen = { commentsOpen = true }) }
        item(key = "related_gap") { Spacer(Modifier.height(12.dp)) }
        items(details.related.filter(hidden::allows), key = { "related_" + it.url }) { ItemView(it) }
    }
}

/** Like with its count and dislike in one capsule, as on YouTube; the current rating is drawn in the accent. */
@Composable
internal fun RatingPill(rating: LikeStatus, likes: Long, dislikes: Long, onRate: (LikeStatus) -> Unit) {
    val colors = MaterialTheme.colorScheme
    fun tint(status: LikeStatus) = if (rating == status) colors.primary else colors.onSurface
    Row(
        Modifier.clip(CapsuleShape).background(colors.surfaceContainerHigh.copy(alpha = 0.85f)).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable { onRate(LikeStatus.LIKE) }.padding(start = 16.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(WIcons.ThumbUp, contentDescription = stringResource(R.string.video_like), modifier = Modifier.size(18.dp), tint = tint(LikeStatus.LIKE))
            Text(
                if (likes >= 0) formatCompact(likes) else stringResource(R.string.video_like),
                style = MaterialTheme.typography.labelSmall,
                color = tint(LikeStatus.LIKE),
            )
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(colors.outlineVariant))
        Row(
            Modifier.clickable { onRate(LikeStatus.DISLIKE) }.padding(start = 12.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(WIcons.ThumbDown, contentDescription = stringResource(R.string.video_dislike), modifier = Modifier.size(18.dp), tint = tint(LikeStatus.DISLIKE))
            if (dislikes >= 0) {
                Text(formatCompact(dislikes), style = MaterialTheme.typography.labelSmall, color = tint(LikeStatus.DISLIKE))
            }
        }
    }
}

@Composable
internal fun ActionPill(icon: ImageVector, text: String, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CapsuleShape)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.85f))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.onSurface)
        Text(text, style = MaterialTheme.typography.labelSmall, color = colors.onSurface)
    }
}

@Composable
private fun Description(html: String, onLink: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .clickable { expanded = !expanded }
            .padding(14.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HtmlText(html, onLink, MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 3)
        Text(
            stringResource(if (expanded) R.string.video_show_less else R.string.video_show_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
