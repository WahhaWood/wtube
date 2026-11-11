@file:androidx.annotation.OptIn(UnstableApi::class)

package dev.wahha.wtube.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.wahha.wtube.R
import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.data.innertube.LikeStatus
import dev.wahha.wtube.data.innertube.ShortItem
import dev.wahha.wtube.player.LocalPlayerState
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.LocalVideoCovering
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.SubscribeButton
import dev.wahha.wtube.ui.components.TabResetEffect
import dev.wahha.wtube.ui.components.VideoMenu
import dev.wahha.wtube.ui.components.errorMessage
import dev.wahha.wtube.ui.components.shareVideo
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.components.viewsText
import dev.wahha.wtube.ui.glass.LocalLiquidGlass
import dev.wahha.wtube.ui.glass.drawInteractiveGlass
import dev.wahha.wtube.ui.glass.rememberBackdrop
import dev.wahha.wtube.ui.glass.rememberGlassInteraction
import dev.wahha.wtube.ui.icon.Comment
import dev.wahha.wtube.ui.icon.MoreVert
import dev.wahha.wtube.ui.icon.PlayArrow
import dev.wahha.wtube.ui.icon.Refresh
import dev.wahha.wtube.ui.icon.Share
import dev.wahha.wtube.ui.icon.SwipeVertical
import dev.wahha.wtube.ui.icon.ThumbDown
import dev.wahha.wtube.ui.icon.ThumbUp
import dev.wahha.wtube.ui.icon.VolumeOff
import dev.wahha.wtube.ui.icon.VolumeUp
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.video.CommentsSheet
import dev.wahha.wtube.ui.video.HtmlText
import dev.wahha.wtube.ui.video.rememberLinkHandler
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private val ButtonSize = 48.dp
private const val PROGRESS_TICK_MS = 100L
private val LOW_WINDOW = 480.dp

/** Fixed: the glass sits over moving video, and sampling it every few seconds would only flicker. */
private const val GLASS_LUMINANCE = 0.5f

/**
 * The Shorts tab: one short per screen, swiped up and down, looping, a tap pausing it. Plays only while
 * on screen: leaving the tab, the app or opening a video stops it.
 */
@Composable
fun ShortsScreen(vm: ShortsViewModel = viewModel()) {
    val context = LocalContext.current
    val feed = vm.feed
    val accountLoaded by Account.loaded.collectAsStateWithLifecycle()
    val session by Account.session.collectAsStateWithLifecycle()
    LaunchedEffect(accountLoaded, session) {
        if (accountLoaded) vm.sync(session)
    }
    val hidden by Hidden.items.collectAsStateWithLifecycle()
    // Kept out once hidden from the menu: by the video at once, by the channel once its page says whose it is.
    val items = feed.items.filter { short ->
        vm.known(short.id)?.details?.item?.let(hidden::allows) ?: hidden.videos.none { it.id == short.id }
    }

    val players = remember { ShortsPlayers(context, vm.viewModelScope) }
    DisposableEffect(players) { onDispose { players.release() } }
    LaunchedEffect(vm.muted) { players.setMuted(vm.muted) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val covering = LocalVideoCovering.current
    val active = lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && !covering
    var userPaused by rememberSaveable { mutableStateOf(false) }
    // Back from the video sheet with that video still playing in the mini player: the short waits for a tap.
    // Decided while the sheet is still open: deciding on the way back lets the short start for a frame, and
    // its audio focus pauses the video in the mini player.
    val mainPlaying = LocalPlayerState.current?.isPlaying == true
    var heldForVideo by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(covering, mainPlaying) {
        if (covering) heldForVideo = mainPlaying
    }

    val pagerState = rememberPagerState(initialPage = vm.current) { items.size }
    val currentItems by rememberUpdatedState(items)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            userPaused = false
            heldForVideo = false
            vm.select(page, currentItems)
        }
    }
    // More of the feed came, or a short went: the neighbours and the next batch follow.
    LaunchedEffect(items.size) {
        if (items.isNotEmpty()) vm.select(pagerState.settledPage.coerceAtMost(items.lastIndex), items)
    }
    TabResetEffect {
        vm.reload()
        pagerState.scrollToPage(0)
    }

    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(colors.scrim).testTag("shorts")) {
        when {
            items.isEmpty() && feed.error != null -> ErrorState(feed.error!!, feed::retry, Modifier.align(Alignment.Center))
            items.isEmpty() && feed.endReached && !feed.loading -> MessageState(
                WIcons.SwipeVertical,
                stringResource(R.string.shorts_empty),
                Modifier.align(Alignment.Center),
            ) {
                FilledTonalButton(onClick = vm::reload) { Text(stringResource(R.string.retry)) }
            }
            items.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> VerticalPager(
                state = pagerState,
                // The neighbours stay composed, so their pictures and pages are there before the swipe.
                beyondViewportPageCount = 1,
                key = { items.getOrNull(it)?.id ?: it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                val item = items.getOrNull(index) ?: return@VerticalPager
                ShortPageView(
                    page = vm.page(item),
                    index = index,
                    players = players,
                    near = abs(index - pagerState.settledPage) <= 1,
                    current = index == pagerState.settledPage,
                    playing = active && !userPaused && !heldForVideo,
                    onTogglePause = { if (heldForVideo) heldForVideo = false else userPaused = !userPaused },
                    muted = vm.muted,
                    onToggleMute = { vm.muted = !vm.muted },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortPageView(
    page: ShortPage,
    index: Int,
    players: ShortsPlayers,
    near: Boolean,
    current: Boolean,
    playing: Boolean,
    onTogglePause: () -> Unit,
    muted: Boolean,
    onToggleMute: () -> Unit,
) {
    val item = page.item
    val player = players.at(index)
    val held = players.holds(index, item.id)
    val colors = MaterialTheme.colorScheme

    // Into its player once near the screen; the stream info is cached by then, or comes with the page.
    LaunchedEffect(near, held, item.id) {
        if (near && !held) runCatching { YouTube.streamInfo(item.id) }.onSuccess { players.bind(index, item.id) }
    }
    LaunchedEffect(held, current, playing) {
        if (!held) return@LaunchedEffect
        if (current) {
            player.playWhenReady = playing
        } else {
            // Back to a short it starts over, as a new one would.
            player.playWhenReady = false
            if (player.currentPosition > 0) player.seekTo(0)
        }
    }
    val playback = rememberPlayback(player, held)
    val onTap by rememberUpdatedState {
        // Paused by the system (another app took the sound, headphones out): a tap plays it again.
        if (playing && playback.pausedBySystem) player.play() else onTogglePause()
    }

    val backdrop = rememberBackdrop(colors.scrim)
    var descriptionOpen by rememberSaveable { mutableStateOf(false) }
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    val onLink = rememberLinkHandler(item.id) { ms -> if (held) player.seekTo(ms) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A landscape phone has no height for the column of buttons: they go in a row.
        val low = maxHeight < LOW_WINDOW
        // What the glass refracts: a sibling of the buttons, never their parent.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AsyncImage(item.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            // Only the short on screen has a surface. A page off screen is never drawn, so its TextureView
            // would get its surface only as the swipe brings it in, and stay opaque black until a frame comes:
            // the neighbours buffer without one and show their first frame as a picture meanwhile.
            if (held && current) {
                ShortVideo(player, playback.size, item.thumbnail)
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(held) {
                detectTapGestures(onTap = { if (held) onTap() })
            },
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.45f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.scrim.copy(alpha = 0.75f)))),
        )
        when {
            page.error != null -> PageError(stringResource(errorMessage(page.error!!)), onRetry = page::load)
            held && playback.error != null -> PageError(stringResource(R.string.shorts_play_failed), onRetry = { players.retry(index, item.id) })
            current && held && (!playing || playback.pausedBySystem) -> Icon(
                WIcons.PlayArrow,
                contentDescription = stringResource(R.string.shorts_play),
                tint = colors.onSurface,
                // On a dark disc: a bare icon is lost on a light frame.
                modifier = Modifier.align(Alignment.Center).size(72.dp).background(colors.scrim.copy(alpha = 0.4f), CircleShape).padding(12.dp),
            )
            current && (!held || playback.buffering) -> CircularProgressIndicator(Modifier.align(Alignment.Center))
        }

        val bottom = systemBottom() + LocalBottomInset.current
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 10.dp, bottom = bottom + 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ShortInfo(page, onOpenDescription = { descriptionOpen = true }, modifier = Modifier.weight(1f))
            ShortActions(page, backdrop, inRow = low, onComments = {
                if (page.comments.list.items.isEmpty() && !page.comments.list.loading) page.comments.list.refresh()
                commentsOpen = true
            })
        }
        if (current && held) ShortProgress(player, Modifier.align(Alignment.BottomStart).padding(bottom = bottom))
        GlassButton(
            backdrop = backdrop,
            icon = if (muted) WIcons.VolumeOff else WIcons.VolumeUp,
            description = stringResource(if (muted) R.string.shorts_sound_on else R.string.shorts_sound_off),
            onClick = onToggleMute,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = systemTop() + 8.dp, end = 10.dp),
        )
    }

    if (commentsOpen) CommentsSheet(page.comments, onLink, onDismiss = { commentsOpen = false })
    if (descriptionOpen) {
        page.details?.let { details ->
            ModalBottomSheet(onDismissRequest = { descriptionOpen = false }, containerColor = colors.surfaceContainerLow) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(details.item.title, style = MaterialTheme.typography.titleMedium)
                    val date = details.uploadedAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM, AppLocale).format(Date(it)) } ?: details.item.uploaded
                    Text(
                        listOfNotNull(viewsText(details.item.views), date).joinToString(stringResource(R.string.meta_separator)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (details.description.isNotBlank()) HtmlText(details.description, onLink, MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The video, cropped to fill the screen when it is upright like the screen, else whole. */
@Composable
private fun ShortVideo(player: ExoPlayer, size: VideoSize, thumbnail: String?) {
    val upright = size.width == 0 || size.height * size.pixelWidthHeightRatio >= size.width
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenUpright = maxHeight >= maxWidth
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = if (upright == screenUpright) ContentScale.Crop else ContentScale.Fit,
            shutter = {
                AsyncImage(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            },
        )
    }
}

@Composable
private fun ShortInfo(page: ShortPage, onOpenDescription: () -> Unit, modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    val haptic = LocalHapticFeedback.current
    val details = page.details ?: return
    val video = details.item
    val state = page.state
    val shadowed = textOnVideo()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f, fill = false).clickable(enabled = video.channelUrl != null) { video.channelUrl?.let(nav.openChannel) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(video.channelAvatar, 32.dp)
                Text(
                    video.channelName.orEmpty(),
                    style = MaterialTheme.typography.titleSmall.merge(shadowed),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val subscribed = state?.subscribed
            if (subscribed != null && state.channelId != null) {
                SubscribeButton(subscribed, video.channelName.orEmpty(), onChange = {
                    haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    page.subscribe(it)
                }, modifier = Modifier.height(36.dp))
            }
        }
        Text(
            video.title,
            style = MaterialTheme.typography.bodyMedium.merge(shadowed),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable(onClick = onOpenDescription).testTag("short_title"),
        )
    }
}

@Composable
private fun ShortActions(page: ShortPage, backdrop: LayerBackdrop, inRow: Boolean, onComments: () -> Unit) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val details = page.details
    val state = page.state
    fun rate(status: LikeStatus) {
        if (state == null) {
            Notices.show(R.string.shorts_sign_in_to_rate)
            return
        }
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        page.rate(status)
    }
    val buttons = @Composable {
        GlassButton(
            backdrop = backdrop,
            icon = WIcons.ThumbUp,
            description = stringResource(R.string.video_like),
            tint = if (state?.like == LikeStatus.LIKE) colors.primary else colors.onSurface,
            label = page.likes.takeIf { it >= 0 }?.let(::formatCompact),
            onClick = { rate(LikeStatus.LIKE) },
        )
        GlassButton(
            backdrop = backdrop,
            icon = WIcons.ThumbDown,
            description = stringResource(R.string.video_dislike),
            tint = if (state?.like == LikeStatus.DISLIKE) colors.primary else colors.onSurface,
            label = page.dislikesShown.takeIf { it >= 0 }?.let(::formatCompact),
            onClick = { rate(LikeStatus.DISLIKE) },
        )
        GlassButton(backdrop, WIcons.Comment, stringResource(R.string.video_comments), onClick = onComments)
        details?.let { GlassButton(backdrop, WIcons.Share, stringResource(R.string.video_share), onClick = { shareVideo(context, it.item) }) }
        details?.let {
            VideoMenu(it.item, recommended = true, anchor = { open ->
                GlassButton(backdrop, WIcons.MoreVert, stringResource(R.string.item_more), onClick = open)
            })
        }
    }
    if (inRow) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons() }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { buttons() }
    }
}

/** A round glass button over the video, with an optional count under it. */
@Composable
private fun GlassButton(
    backdrop: LayerBackdrop,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    label: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = rememberGlassInteraction()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.size(ButtonSize)
                .drawInteractiveGlass(
                    backdrop = backdrop,
                    layer = null,
                    luminance = { GLASS_LUMINANCE },
                    shape = CircleShape,
                    interaction = interaction,
                    fallbackColor = colors.surfaceContainerHigh.copy(alpha = 0.6f),
                    enabled = LocalLiquidGlass.current,
                )
                .clickable(interactionSource = null, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = tint)
        }
        if (label != null) Text(label, style = MaterialTheme.typography.labelSmall.merge(textOnVideo()), maxLines = 1)
    }
}

@Composable
private fun BoxScope.PageError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium.merge(textOnVideo()))
        FilledTonalButton(onClick = onRetry) {
            Icon(WIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.retry), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** A thin line of how far the short has played, along the bottom edge of the content. */
@Composable
private fun ShortProgress(player: Player, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            fraction = if (duration > 0) (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            delay(PROGRESS_TICK_MS)
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth().height(2.dp).background(colors.onSurface.copy(alpha = 0.2f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(colors.primary))
    }
}

/** Text over a moving picture keeps a soft shadow, so light frames do not swallow it. */
@Composable
private fun textOnVideo(): TextStyle =
    TextStyle(color = MaterialTheme.colorScheme.onSurface, shadow = Shadow(MaterialTheme.colorScheme.scrim, blurRadius = 8f))

/** What the page needs to know of its player: size, buffering, error. */
private class Playback {
    var pausedBySystem by mutableStateOf(false)
    var size by mutableStateOf(VideoSize.UNKNOWN)
    var buffering by mutableStateOf(false)
    var error by mutableStateOf<PlaybackException?>(null)
}

@Composable
private fun rememberPlayback(player: Player, held: Boolean): Playback {
    val playback = remember(player) { Playback() }
    DisposableEffect(player, held) {
        fun sync() {
            playback.size = player.videoSize
            playback.buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            playback.error = player.playerError
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playback.pausedBySystem = !playWhenReady &&
                    (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS || reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
            }
        }
        sync()
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    return playback
}
