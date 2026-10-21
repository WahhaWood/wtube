package dev.wahha.wtube.ui.components

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.data.WatchProgress
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import coil3.compose.AsyncImage
import dev.wahha.wtube.R
import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.LinkTarget
import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.formatAgo
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.data.formatDuration
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.player.LocalPlayerState
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.ui.downloads.DownloadSheet
import dev.wahha.wtube.ui.icon.AccountCircle
import dev.wahha.wtube.ui.icon.AddToQueue
import dev.wahha.wtube.ui.icon.Block
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.icon.MoreVert
import dev.wahha.wtube.ui.icon.PlaylistAdd
import dev.wahha.wtube.ui.icon.PlaylistPlay
import dev.wahha.wtube.ui.icon.QueuePlayNext
import dev.wahha.wtube.ui.icon.Share
import dev.wahha.wtube.ui.icon.Verified
import dev.wahha.wtube.ui.icon.VisibilityOff
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.icon.WatchLater

/** Where list items lead; provided once by the app shell so screens need not thread lambdas. */
class Navigator(
    private val showVideo: (String, VideoOrigin?) -> Unit,
    val openChannel: (String) -> Unit,
    val openPlaylist: (String) -> Unit,
    val openSearch: () -> Unit,
    val openSettings: () -> Unit,
    val openLogin: () -> Unit,
    /** A list of the signed-in account by its InnerTube browse id (history, channels). */
    val openFeed: (String) -> Unit,
    val openDownloads: () -> Unit,
    /** The whole local watch history. */
    val openHistory: () -> Unit,
    val back: () -> Unit,
) {
    /** [from] is the tapped card's thumbnail, for the page to grow out of. */
    fun openVideo(id: String, from: VideoOrigin? = null) = showVideo(id, from)

    /** Opens what a YouTube link points to; a channel's live page opens its stream, or the channel when it is not live. */
    suspend fun open(target: LinkTarget) {
        when (target) {
            is LinkTarget.Video -> openVideo(target.id)
            is LinkTarget.Playlist -> openPlaylist(target.url)
            is LinkTarget.Channel -> openChannel(target.url)
            is LinkTarget.Live -> runCatching { YouTube.liveVideoId(target.url) }.getOrNull()?.let(::openVideo)
                ?: openChannel(target.url.removeSuffix("/").removeSuffix("/live"))
        }
    }
}

/** A thumbnail's place in the window at the moment of a tap. */
@Immutable
class VideoOrigin(val bounds: Rect, val thumbnail: String?, val corner: Dp)

/** Keeps hold of a thumbnail's layout, so its place is only worked out when the card is tapped. */
class ThumbnailAnchor(private val corner: Dp) {
    private var coordinates: LayoutCoordinates? = null

    val modifier = Modifier.onPlaced { coordinates = it }

    fun origin(thumbnail: String?): VideoOrigin? = coordinates?.takeIf { it.isAttached }?.let {
        VideoOrigin(Rect(it.positionInWindow(), it.size.toSize()), thumbnail, corner)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }

/** Extra bottom padding lists need to clear the floating bar and the mini player. */
val LocalBottomInset = staticCompositionLocalOf { 0.dp }

private val ThumbnailCorner = 12.dp
private val ThumbnailShape = RoundedCornerShape(ThumbnailCorner)

@Composable
fun ItemView(item: Item, compact: Boolean = false, progress: Float = 0f) {
    val nav = LocalNavigator.current
    when (item) {
        is VideoItem -> if (compact) VideoRow(item, progress) { nav.openVideo(item.id, it) } else VideoCard(item) { nav.openVideo(item.id, it) }
        is ChannelItem -> ChannelRow(item) { nav.openChannel(item.url) }
        is PlaylistItem -> PlaylistRow(item) { nav.openPlaylist(item.url) }
    }
}

/** Full-width card for feeds: edge-to-edge thumbnail, then avatar, title and meta, like the YouTube app. */
@Composable
fun VideoCard(video: VideoItem, onClick: (VideoOrigin?) -> Unit) {
    val nav = LocalNavigator.current
    val anchor = remember { ThumbnailAnchor(0.dp) }
    Column(Modifier.fillMaxWidth().clickable { onClick(anchor.origin(video.thumbnail)) }.padding(bottom = 12.dp)) {
        val watched = watchedFraction(video.id)
        Thumbnail(video.thumbnail, Modifier.fillMaxWidth().then(anchor.modifier)) {
            VideoBadge(video)
            WatchedBar(watched)
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (video.channelAvatar != null) {
                Avatar(
                    video.channelAvatar,
                    36.dp,
                    Modifier.clickable(enabled = video.channelUrl != null) { video.channelUrl?.let(nav.openChannel) },
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    videoMeta(video, withChannel = true),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            VideoMenu(video, Modifier.offset(x = 8.dp, y = (-8).dp))
        }
    }
}

/**
 * "⋮" on a video: the queue, the way to its channel, saving with an account, downloading, sharing, and
 * keeping it or its channel out of recommendations.
 */
@Composable
fun VideoMenu(video: VideoItem, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    // With nothing playing there is nothing to queue after: the video just starts.
    val playing = LocalPlayerState.current?.mediaId != null
    val session by Account.session.collectAsState()
    val signedIn = session?.expired == false
    var open by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    if (saving) SaveSheet(video.id, onDismiss = { saving = false })
    if (downloading) DownloadSheet(video, onDismiss = { downloading = false })
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(WIcons.MoreVert, contentDescription = stringResource(R.string.item_more), modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_play_next)) },
                leadingIcon = { Icon(WIcons.QueuePlayNext, contentDescription = null) },
                onClick = {
                    open = false
                    if (playing) {
                        PlayQueue.playNext(video)
                        Notices.show(R.string.queue_next_added)
                    } else {
                        nav.openVideo(video.id)
                    }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_add)) },
                leadingIcon = { Icon(WIcons.AddToQueue, contentDescription = null) },
                onClick = {
                    open = false
                    if (playing) {
                        PlayQueue.add(video)
                        Notices.show(R.string.queue_added)
                    } else {
                        nav.openVideo(video.id)
                    }
                },
            )
            video.channelUrl?.let { url ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.item_open_channel)) },
                    leadingIcon = { Icon(WIcons.AccountCircle, contentDescription = null) },
                    onClick = {
                        open = false
                        nav.openChannel(url)
                    },
                )
            }
            if (signedIn) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_watch_later)) },
                    leadingIcon = { Icon(WIcons.WatchLater, contentDescription = null) },
                    onClick = {
                        open = false
                        launchAction(R.string.watch_later_added) { Actions.setInPlaylist(Actions.WATCH_LATER, video.id, add = true) }
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.item_save_to_playlist)) },
                    leadingIcon = { Icon(WIcons.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        open = false
                        saving = true
                    },
                )
            }
            if (!video.isLive) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.downloads_action)) },
                    leadingIcon = { Icon(WIcons.Download, contentDescription = null) },
                    onClick = {
                        open = false
                        downloading = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.video_share)) },
                leadingIcon = { Icon(WIcons.Share, contentDescription = null) },
                onClick = {
                    open = false
                    shareVideo(context, video)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.item_not_interested)) },
                leadingIcon = { Icon(WIcons.VisibilityOff, contentDescription = null) },
                onClick = {
                    open = false
                    Hidden.hideVideo(video)
                    Notices.show(R.string.hidden_video_done)
                },
            )
            video.channelUrl?.let { url ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.item_dont_recommend)) },
                    leadingIcon = { Icon(WIcons.Block, contentDescription = null) },
                    onClick = {
                        open = false
                        val name = video.channelName.orEmpty()
                        Hidden.hideChannel(url, name, video.channelAvatar)
                        Notices.show(R.string.hidden_channel_done, name)
                    },
                )
            }
        }
    }
}

fun shareVideo(context: Context, video: VideoItem) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, video.url)
    context.startActivity(Intent.createChooser(send, null))
}

/**
 * How much of a video was watched, here or on the account's other devices; 0 when not started. Only a
 * change of this video's share recomposes the card.
 */
@Composable
private fun watchedFraction(id: String): Float {
    val fractions = WatchProgress.fractions.collectAsStateWithLifecycle()
    val fraction by remember(id) { derivedStateOf { fractions.value[id] ?: 0f } }
    return fraction
}

/** YouTube's bar along the bottom of a thumbnail: how much of the video was watched. */
@Composable
private fun BoxScope.WatchedBar(fraction: Float) {
    if (fraction <= 0f) return
    LinearProgressIndicator(
        progress = { fraction },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
        drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/** Dense row for related videos, playlists, channels and history. */
@Composable
fun VideoRow(video: VideoItem, progress: Float = 0f, onClick: (VideoOrigin?) -> Unit) {
    val anchor = remember { ThumbnailAnchor(ThumbnailCorner) }
    Row(
        Modifier.fillMaxWidth().clickable { onClick(anchor.origin(video.thumbnail)) }.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val watched = watchedFraction(video.id)
        Thumbnail(video.thumbnail, Modifier.width(168.dp).clip(ThumbnailShape).then(anchor.modifier)) {
            VideoBadge(video)
            WatchedBar(if (progress > 0f) progress else watched)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            video.channelName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                videoMeta(video, withChannel = false),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        VideoMenu(video, Modifier.offset(x = 8.dp, y = (-6).dp))
    }
}

@Composable
fun ChannelRow(channel: ChannelItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatar, 64.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (channel.verified) VerifiedBadge()
            }
            Text(
                subscribersText(channel.subscribers) ?: stringResource(R.string.channel),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun PlaylistRow(playlist: PlaylistItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(playlist.thumbnail, Modifier.width(168.dp).clip(ThumbnailShape)) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(WIcons.PlaylistPlay, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
                if (playlist.count >= 0) {
                    Text(playlist.count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(playlist.uploader, stringResource(R.string.playlist)).joinToString(stringResource(R.string.meta_separator)),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun Thumbnail(url: String?, modifier: Modifier = Modifier, overlay: @Composable BoxScope.() -> Unit = {}) {
    Box(modifier.aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

@Composable
fun Avatar(url: String?, size: Dp, modifier: Modifier = Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
    )
}

@Composable
fun VerifiedBadge() {
    Icon(WIcons.Verified, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun BoxScope.VideoBadge(video: VideoItem) {
    val colors = MaterialTheme.colorScheme
    val (text, background) = when {
        video.isLive -> stringResource(R.string.live) to colors.error
        video.duration > 0 -> formatDuration(video.duration) to colors.scrim.copy(alpha = 0.75f)
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (video.isLive) colors.onError else colors.onSurface,
        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
            .background(background, RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun videoMeta(video: VideoItem, withChannel: Boolean): String = listOfNotNull(
    video.channelName.takeIf { withChannel },
    viewsText(video.views),
    if (video.isLive) null else video.uploadedAt?.let { formatAgo(it, System.currentTimeMillis()) } ?: video.uploaded,
).joinToString(stringResource(R.string.meta_separator))

@Composable
fun viewsText(views: Long): String? = when {
    views < 0 -> null
    views < 1000 -> pluralStringResource(R.plurals.views, views.toInt(), views.toString())
    // Russian takes the genitive plural after "тыс." / "млн": quantity 5 selects it.
    else -> pluralStringResource(R.plurals.views, 5, formatCompact(views))
}

@Composable
fun subscribersText(count: Long): String? = when {
    count < 0 -> null
    count < 1000 -> pluralStringResource(R.plurals.subscribers, count.toInt(), count.toString())
    else -> pluralStringResource(R.plurals.subscribers, 5, formatCompact(count))
}
