package dev.wahha.wtube.ui.video

import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Chapter
import dev.wahha.wtube.data.Comment
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.formatAgo
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.data.parseYoutubeTime
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.TextEntryDialog
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.glass.CapsuleShape
import dev.wahha.wtube.ui.icon.Send
import dev.wahha.wtube.ui.icon.ThumbUp
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.icon.Close

private val ChannelPaths = listOf("/@", "/channel/", "/c/", "/user/")

/**
 * Where a link in a description or comment leads: a timestamp of this video seeks, other YouTube
 * videos and channels open inside the app, anything else goes to the browser.
 */
@Composable
fun rememberLinkHandler(videoId: String, onSeek: (Long) -> Unit): (String) -> Unit {
    val nav = LocalNavigator.current
    val uriHandler = LocalUriHandler.current
    return remember(videoId, nav, uriHandler, onSeek) {
        { url ->
            val uri = Uri.parse(url)
            val linkedVideo = YouTube.videoIdOf(url)
            val seconds = uri.getQueryParameter("t")?.let(::parseYoutubeTime)
            val youtubeHost = uri.host?.endsWith("youtube.com") == true
            when {
                linkedVideo == videoId && seconds != null -> onSeek(seconds * 1000)
                linkedVideo != null -> nav.openVideo(linkedVideo)
                youtubeHost && ChannelPaths.any { uri.path.orEmpty().startsWith(it) } -> nav.openChannel(url)
                else -> runCatching { uriHandler.openUri(url) }
            }
        }
    }
}

@Composable
fun HtmlText(
    html: String,
    onLink: (String) -> Unit,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val text = remember(html, linkColor, onLink) {
        AnnotatedString.fromHtml(
            html,
            linkStyles = TextLinkStyles(style = SpanStyle(color = linkColor)),
            linkInteractionListener = { link -> (link as? LinkAnnotation.Url)?.url?.let(onLink) },
        )
    }
    Text(text, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

@Composable
fun Chapters(chapters: List<Chapter>, onSeek: (Long) -> Unit) {
    Column {
        Text(
            stringResource(R.string.video_chapters),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapters, key = { it.startSeconds }) { chapter ->
                Column(
                    Modifier.width(150.dp).clickable { onSeek(chapter.startSeconds * 1000L) },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Thumbnail(chapter.thumbnail, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
                    Text(formatClock(chapter.startSeconds * 1000L), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(chapter.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** The comments card under the description: the first comment as a teaser; tapping opens them all. */
@Composable
fun CommentsPreview(comments: PagedList<Comment>, onOpen: () -> Unit) {
    val first = comments.items.firstOrNull()
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f))
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.video_comments), style = MaterialTheme.typography.titleSmall)
        when {
            first != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(first.avatar, 24.dp)
                Text(
                    AnnotatedString.fromHtml(first.html).text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            comments.loading -> Box(Modifier.size(1.dp))
            comments.error == null -> Text(stringResource(R.string.comments_empty), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(vm: VideoViewModel, comments: PagedList<Comment>, onLink: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ImmersiveDialog()
        CommentsContent(vm, comments, onLink, listModifier = Modifier.fillMaxHeight(0.85f))
    }
}

/** Title, sorting, the account's comment field and the threads: in the bottom sheet or the fullscreen side panel. */
@Composable
fun ColumnScope.CommentsContent(
    vm: VideoViewModel,
    comments: PagedList<Comment>,
    onLink: (String) -> Unit,
    listModifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    var replyTo by remember { mutableStateOf<Comment?>(null) }
    LoadMoreEffect(listState) { comments.loadMore() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.video_comments),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 8.dp),
        )
        if (onClose != null) {
            IconButton(onClick = onClose) {
                Icon(WIcons.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
    if (vm.commentSorts.size >= 2) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.comments_sort_top, R.string.comments_sort_new).forEachIndexed { index, label ->
                FilterChip(
                    selected = vm.commentSort == index,
                    onClick = { vm.sortComments(index) },
                    label = { Text(stringResource(label)) },
                )
            }
        }
    }
    if (vm.commentBox != null) CommentBox(vm::postComment)
    LazyColumn(state = listState, modifier = listModifier) {
        comments.items.forEach { comment ->
            item(key = comment.id) {
                CommentRow(
                    comment, onLink, liked = vm.isLiked(comment), onLike = { vm.likeComment(comment) }, onReply = { replyTo = comment },
                    repliesOpen = comment.id in vm.replies, onReplies = { vm.toggleReplies(comment) },
                )
            }
            vm.replies[comment.id]?.let { replies ->
                items(replies.items, key = { "reply_${comment.id}_${it.id}" }) { reply ->
                    CommentRow(
                        reply, onLink, liked = vm.isLiked(reply), onLike = { vm.likeComment(reply) }, onReply = { replyTo = reply },
                        modifier = Modifier.padding(start = 44.dp),
                    )
                }
                item(key = "replies_footer_${comment.id}") {
                    if (replies.loading || replies.error != null) {
                        ListFooter(replies.loading, replies.error, replies::retry)
                    } else if (!replies.endReached) {
                        TextButton(onClick = replies::loadMore, modifier = Modifier.padding(start = 92.dp)) {
                            Text(stringResource(R.string.comments_more_replies))
                        }
                    }
                }
            }
        }
        item(key = "footer") { ListFooter(comments.loading, comments.error, comments::retry) }
    }
    replyTo?.let { comment ->
        TextEntryDialog(
            title = stringResource(R.string.comment_reply_to, comment.author),
            placeholder = stringResource(R.string.comment_reply),
            confirm = stringResource(R.string.comment_send),
            onDismiss = { replyTo = null },
            onConfirm = { text ->
                replyTo = null
                vm.reply(comment, text)
            },
        )
    }
}

/** The account's own comment field, at the top as in the YouTube app. */
@Composable
private fun CommentBox(onSend: (String) -> Unit) {
    val session by Account.session.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(session?.avatar, 32.dp)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(stringResource(R.string.comment_placeholder), style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            maxLines = 4,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = {
                onSend(text.trim())
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(WIcons.Send, contentDescription = stringResource(R.string.comment_send))
        }
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    onLink: (String) -> Unit,
    liked: Boolean,
    onLike: () -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
    repliesOpen: Boolean = false,
    onReplies: () -> Unit = {},
) {
    val nav = LocalNavigator.current
    val actions = comment.actions
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Avatar(comment.avatar, 32.dp, Modifier.clickable(enabled = comment.authorUrl != null) { comment.authorUrl?.let(nav.openChannel) })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val posted = comment.postedAt?.let { formatAgo(it, System.currentTimeMillis()) } ?: comment.posted
            Text(
                listOfNotNull(
                    if (comment.pinned) stringResource(R.string.comment_pinned) else null,
                    comment.author,
                    posted,
                ).joinToString(stringResource(R.string.meta_separator)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HtmlText(comment.html, onLink, MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // The count moves by what was liked here: the list's own count already has the account's old like.
                val likes = comment.likes + (if (liked) 1 else 0) - (if (actions?.liked == true) 1 else 0)
                val canLike = actions != null && (if (liked) actions.unlike else actions.like) != null
                Row(
                    Modifier.clip(CapsuleShape).clickable(enabled = canLike, onClick = onLike).padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        WIcons.ThumbUp,
                        contentDescription = stringResource(R.string.video_like),
                        modifier = Modifier.size(14.dp),
                        tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    )
                    if (likes > 0) Text(formatCompact(likes.toLong()), style = MaterialTheme.typography.bodySmall)
                }
                if (actions?.replyParams != null) {
                    Text(
                        stringResource(R.string.comment_reply),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 6.dp).clip(CapsuleShape).clickable(onClick = onReply).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (comment.replies > 0 || comment.repliesPage != null) {
                    Text(
                        if (repliesOpen) {
                            stringResource(R.string.comments_hide_replies)
                        } else if (comment.replies > 0) {
                            pluralStringResource(R.plurals.comment_replies, comment.replies, comment.replies.toString())
                        } else {
                            stringResource(R.string.comments_show_replies)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp).clip(CapsuleShape)
                            .clickable(enabled = comment.repliesPage != null, onClick = onReplies)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
