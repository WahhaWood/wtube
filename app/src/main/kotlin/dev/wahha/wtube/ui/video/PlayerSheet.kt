package dev.wahha.wtube.ui.video

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.media3.common.Player
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.wahha.wtube.LocalPictureInPicture
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.player.LocalPlayerConnection
import dev.wahha.wtube.player.LocalPlayerState
import dev.wahha.wtube.ui.components.VideoOrigin
import dev.wahha.wtube.ui.glass.LocalLiquidGlass
import dev.wahha.wtube.ui.glass.drawInteractiveGlass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private val SettleSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
private val LaunchSpec = tween<Float>(durationMillis = 380, easing = FastOutSlowInEasing)
private val FlingVelocity = 400.dp
private val FullscreenSwipe = 48.dp

/** "Previous" restarts the video when it has played longer than this. */
private const val RESTART_MS = 3_000L
private const val SCRIM_ALPHA = 0.6f

/**
 * The watch page as a sheet over the whole app, after the YouTube app. [progress] 0 is the full page, 1 the
 * mini player above the tab bar; drags, the back gesture and taps all move this one number, so the video,
 * the page under it, the tab bar and the dim behind travel together instead of as separate animations.
 */
@Stable
class PlayerSheetState(private val scope: CoroutineScope) {
    var progress by mutableFloatStateOf(1f)
        private set

    /** Where the sheet rests or is heading; a drag does not change it until the finger lifts. */
    var expanded by mutableStateOf(false)
        private set

    /** Sideways offset of the mini player while it is swiped away, in px. */
    var dismissOffset by mutableFloatStateOf(0f)
        private set

    // Set by the layout: px the sheet's top travels between the two states, the window width, a fling.
    internal var range = 1f
    internal var width = 1f
    internal var flingVelocity = 1f

    /** True between a drag's first move and its release, so a list's fling knows the sheet is its to settle. */
    internal var dragging = false
        private set

    /** The card thumbnail the page is growing out of while it opens, or null. */
    var origin by mutableStateOf<VideoOrigin?>(null)
        private set

    /** How far the page still is from its place while it grows out of [origin]: 1 at the card, 0 settled. */
    var launch by mutableFloatStateOf(0f)
        private set

    /** How much the page covers the app: 1 fully open, 0 the mini player or a card about to grow. */
    val coverage get() = (1f - progress) * (1f - launch)

    private var job: Job? = null

    fun expand() = settleTo(expanded = true)

    /** Opens the page out of a card's thumbnail, as a shared element would. */
    fun expandFrom(from: VideoOrigin) {
        job?.cancel()
        dragging = false
        expanded = true
        progress = 0f
        dismissOffset = 0f
        origin = from
        launch = 1f
        job = scope.launch {
            try {
                animate(1f, 0f, animationSpec = LaunchSpec) { value, _ -> launch = value }
            } finally {
                // A drag or the back gesture cuts the growth short; the page is then simply in its place.
                // Runs after cancellation too, so a newer launch from another card must be left alone.
                if (origin === from) {
                    launch = 0f
                    origin = null
                }
            }
        }
    }

    fun collapse() = settleTo(expanded = false)

    /** Straight to the mini player, e.g. for a video that was already playing when the app opened. */
    fun snapCollapsed() {
        job?.cancel()
        dragging = false
        expanded = false
        progress = 1f
        dismissOffset = 0f
    }

    internal fun dragBy(deltaPx: Float) {
        job?.cancel()
        dragging = true
        progress = (progress + deltaPx / range).coerceIn(0f, 1f)
    }

    /** Ends a drag: a fling picks the direction, otherwise the nearer end wins. */
    internal fun settle(velocityPx: Float) {
        val toExpanded = when {
            velocityPx > flingVelocity -> false
            velocityPx < -flingVelocity -> true
            else -> progress < 0.5f
        }
        settleTo(toExpanded, velocityPx / range)
    }

    /** The back gesture leads, as in LibreTube: the sheet sits wherever the gesture's own progress is. */
    internal fun follow(fraction: Float) {
        job?.cancel()
        progress = fraction.coerceIn(0f, 1f)
    }

    internal fun dismissBy(deltaPx: Float) {
        job?.cancel()
        dismissOffset += deltaPx
    }

    /** Past a third of the width, or flung, the mini player leaves the screen and [onDismissed] runs. */
    internal fun settleDismiss(velocityPx: Float, onDismissed: () -> Unit) {
        val away = abs(dismissOffset) > width / 3f || abs(velocityPx) > flingVelocity
        val direction = if (abs(velocityPx) > flingVelocity) sign(velocityPx) else sign(dismissOffset)
        val target = if (away) direction * width else 0f
        job?.cancel()
        job = scope.launch {
            animate(dismissOffset, target, velocityPx, SettleSpec) { value, _ -> dismissOffset = value }
            if (away) onDismissed()
        }
    }

    private fun settleTo(expanded: Boolean, velocity: Float = 0f) {
        this.expanded = expanded
        dragging = false
        if (expanded) dismissOffset = 0f
        job?.cancel()
        job = scope.launch {
            animate(progress, if (expanded) 0f else 1f, velocity, SettleSpec) { value, _ ->
                // A fast fling makes even a non-bouncy spring overshoot; past either end the layout breaks.
                progress = value.coerceIn(0f, 1f)
            }
        }
    }
}

/**
 * Draws [vm]'s video between the full page and the mini player. The same [VideoPlayer] is in both states:
 * collapsing shrinks it into the mini player's slot, so the picture never restarts or blinks.
 * [miniWidth] and [miniBottom] place the mini player above the tab bar (its distance from the window's bottom).
 */
@Composable
fun PlayerSheet(
    vm: VideoViewModel,
    sheet: PlayerSheetState,
    miniWidth: Dp,
    miniBottom: Dp,
    backdrop: LayerBackdrop,
    luminance: () -> Float,
    onClose: () -> Unit,
) {
    val connection = LocalPlayerConnection.current
    val player = connection.player
    val state = LocalPlayerState.current
    val details = vm.details
    val colors = MaterialTheme.colorScheme

    // Hand the video to the player once its stream info is cached (the session reads it from there).
    LaunchedEffect(details, player, vm.playPending) {
        if (details != null && player != null && vm.playPending) {
            vm.playStarted()
            connection.play(details.item.id)
            History.record(details.item)
        }
    }
    // A video that failed to open must not leave the previous one playing under its error.
    LaunchedEffect(vm.error, player) {
        if (vm.error != null && vm.playPending && player != null && player.currentMediaItem?.mediaId != vm.id) connection.stop()
    }

    // Orientation we forced with the fullscreen button; null follows the sensor, and a phone turned
    // sideways is fullscreen by itself, as in the YouTube app.
    var orientationLock by rememberSaveable { mutableStateOf<Int?>(null) }
    // Fullscreen held upright: a vertical video fills the screen without turning it.
    var uprightFullscreen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sheet.expanded) {
        if (!sheet.expanded) {
            orientationLock = null
            uprightFullscreen = false
        }
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val fullscreen = sheet.expanded && when (orientationLock) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> true
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> uprightFullscreen
        else -> landscape
    }
    val vertical = state?.videoSize?.let { it.height > 0 && it.width * it.pixelWidthHeightRatio < it.height } == true
    val toggleFullscreen = {
        when {
            fullscreen -> {
                orientationLock = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                uprightFullscreen = false
            }
            vertical -> {
                orientationLock = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                uprightFullscreen = true
            }
            else -> orientationLock = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
    var panel by remember { mutableStateOf<FullscreenPanel?>(null) }
    LaunchedEffect(fullscreen) { if (!fullscreen) panel = null }
    val pictureInPicture = LocalPictureInPicture.current
    val inPictureInPicture = pictureInPicture?.active == true
    val settings by Settings.current.collectAsStateWithLifecycle()
    val pipAllowed = state?.isPlaying == true && settings.pictureInPicture
    DisposableEffect(pictureInPicture, pipAllowed) {
        pictureInPicture?.setEnabled(pipAllowed)
        onDispose { pictureInPicture?.setEnabled(false) }
    }
    if (sheet.expanded && !inPictureInPicture) {
        FullscreenEffect(fullscreen, orientationLock)
        ReleaseOrientationLock(orientationLock.takeUnless { uprightFullscreen }) { orientationLock = null }
    }
    KeepScreenOn(
        state != null && state.playWhenReady &&
            (state.playbackState == Player.STATE_READY || state.playbackState == Player.STATE_BUFFERING),
    )

    // Back leaves fullscreen first, then collapses the page into the mini player along with the gesture.
    BackHandler(enabled = fullscreen && !inPictureInPicture, onBack = toggleFullscreen)
    // Registered later, so it goes first: back closes a fullscreen panel before leaving fullscreen.
    BackHandler(enabled = panel != null && !inPictureInPicture) { panel = null }
    PredictiveBackHandler(enabled = sheet.expanded && !fullscreen && !inPictureInPicture) { events ->
        try {
            events.collect { sheet.follow(it.progress) }
            sheet.collapse()
        } catch (e: CancellationException) {
            sheet.expand()
            throw e
        }
    }

    // The picture-in-picture window shows the video alone, whatever state the sheet was in.
    val progress = { if (inPictureInPicture) 0f else sheet.progress }
    val videoFills = fullscreen || inPictureInPicture
    val miniMode by remember { derivedStateOf { sheet.progress > 0.5f } }
    val showMini = miniMode && !inPictureInPicture
    // The chat polls only while it can be seen: on the page or beside the fullscreen video, with the app in front.
    val chat = vm.chat
    val chatVisible = (!miniMode || fullscreen) && !inPictureInPicture
    val currentPlayer by rememberUpdatedState(player)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (chat != null && chatVisible) {
        LaunchedEffect(chat, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { chat.follow { currentPlayer?.currentPosition ?: 0 } }
        }
    }
    val controlsShown by remember { derivedStateOf { sheet.progress == 0f && sheet.launch == 0f } }
    val density = LocalDensity.current
    val statusBar = WindowInsets.statusBars.getTop(density)
    val glass = LocalLiquidGlass.current
    val pageScroll = remember(sheet) { sheetScrollConnection(sheet) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val geometry = with(density) {
            SheetGeometry(
                width = constraints.maxWidth.toFloat(),
                height = constraints.maxHeight.toFloat(),
                statusBar = statusBar.toFloat(),
                miniWidth = miniWidth.toPx(),
                miniBottom = miniBottom.toPx(),
                miniHeight = MiniPlayerHeight.toPx(),
                miniVideoStart = MiniVideoStart.toPx(),
                miniVideoWidth = MiniVideoWidth.toPx(),
            )
        }
        SideEffect {
            sheet.range = geometry.miniTop.coerceAtLeast(1f)
            sheet.width = geometry.width
            sheet.flingVelocity = with(density) { FlingVelocity.toPx() }
        }

        if (!inPictureInPicture) {
            Box(Modifier.fillMaxSize().drawBehind { drawRect(colors.scrim, alpha = sheet.coverage * SCRIM_ALPHA) })
        }

        Layout(
            contents = listOf(
                {
                    // A child of its own: toggling it must not change the container's modifiers mid-drag.
                    if (showMini) {
                        Box(
                            Modifier.fillMaxSize().drawInteractiveGlass(
                                backdrop = backdrop,
                                layer = null,
                                luminance = luminance,
                                shape = RoundedCornerShape(MiniPlayerRadius),
                                interaction = null,
                                fallbackColor = colors.surfaceContainerHigh,
                                enabled = glass,
                            ),
                        )
                    }
                },
                {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha(progress()) }.background(colors.background)) {
                        if (!videoFills) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = pageContentAlpha(progress()) }) {
                                if (settings.ambientMode) AmbientGlow(details?.item?.thumbnail)
                                // Solid fill behind the status bar; the ambient glow only shows below the player.
                                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(colors.scrim))
                            }
                        }
                    }
                },
                {
                    if (!videoFills) {
                        VideoInfo(
                            vm,
                            onSeek = { ms -> player?.run { seekTo(ms); play() } },
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer { alpha = pageContentAlpha(progress()) * launchContentAlpha(sheet.launch) }
                                .nestedScroll(pageScroll),
                        )
                    }
                },
                {
                    // Sheets opened over the fullscreen video hide the system bars in their windows too.
                    CompositionLocalProvider(LocalFullscreen provides fullscreen) {
                        val videoModifier = Modifier.fillMaxSize()
                            .graphicsLayer {
                                shape = RoundedCornerShape(MiniVideoRadius * progress())
                                clip = true
                            }
                            .pointerInputVideoDrag(sheet, fullscreen, inPictureInPicture, toggleFullscreen) {
                                panel = FullscreenPanel.MoreVideos
                            }
                        if (player != null && state != null) {
                            VideoPlayer(
                                player = player,
                                state = state,
                                title = details?.item?.title,
                                channel = details?.item?.channelName,
                                thumbnail = details?.item?.thumbnail ?: state.metadata.artworkUri?.toString(),
                                storyboard = details?.storyboard.orEmpty(),
                                chapters = details?.chapters.orEmpty(),
                                heatmap = vm.heatmap,
                                fullscreen = fullscreen,
                                onToggleFullscreen = toggleFullscreen,
                                onCollapse = if (fullscreen) toggleFullscreen else sheet::collapse,
                                onRetry = connection::retry,
                                // As in YouTube: back to the start first, to the video before only from near it.
                                onPrevious = {
                                    if (player.currentPosition > RESTART_MS || !vm.hasPrevious) player.seekTo(0) else vm.playPrevious()
                                },
                                onNext = vm::playNext.takeIf { vm.hasNext },
                                modifier = videoModifier,
                                showControls = controlsShown && !inPictureInPicture && panel != FullscreenPanel.MoreVideos,
                                fullscreenActions = { FullscreenActions(vm, onPanel = { panel = it }) },
                            )
                        } else {
                            Box(videoModifier.background(colors.scrim))
                        }
                        // The card's own picture covers whatever the player shows until the page is in place.
                        sheet.origin?.let { origin ->
                            AsyncImage(
                                model = origin.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = (sheet.launch / 0.3f).coerceIn(0f, 1f) },
                            )
                        }
                        if (fullscreen && !inPictureInPicture) FullscreenPanels(
                            vm,
                            panel,
                            onPanel = { panel = it },
                            onSeek = { ms -> player?.run { seekTo(ms); play() } },
                        )
                    }
                },
                {
                    if (showMini && player != null && state != null) {
                        MiniPlayerContent(
                            player = player,
                            state = state,
                            title = details?.item?.title ?: state.metadata.title?.toString().orEmpty(),
                            author = details?.item?.channelName ?: state.metadata.artist?.toString().orEmpty(),
                            onClose = onClose,
                            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = miniAlpha(progress()) }.testTag("mini_player"),
                        )
                    }
                },
            ),
            modifier = Modifier
                .layout { measurable, _ ->
                    val box = geometry.container(progress(), videoFills).let { page ->
                        sheet.origin?.let { lerp(page, it.bounds, sheet.launch) } ?: page
                    }
                    val placeable = measurable.measure(
                        Constraints.fixed(box.width.roundToInt().coerceAtLeast(0), box.height.roundToInt().coerceAtLeast(0)),
                    )
                    layout(placeable.width, placeable.height) {
                        placeable.place(IntOffset((box.left + sheet.dismissOffset).roundToInt(), box.top.roundToInt()))
                    }
                }
                .graphicsLayer {
                    alpha = 1f - (abs(sheet.dismissOffset) / geometry.width).coerceIn(0f, 1f)
                    shape = RoundedCornerShape(MiniPlayerRadius * progress() + (sheet.origin?.corner ?: 0.dp) * sheet.launch)
                    clip = true
                }
                // Also keeps touches on the page from falling through to the screen underneath.
                .pointerInput(sheet) { miniPlayerDrags(sheet, onClose) }
                .pointerInput(sheet) { detectTapGestures { if (sheet.progress > 0.5f) sheet.expand() } }
                .testTag("player_sheet")
                .semantics { stateDescription = if (sheet.expanded) "expanded" else "collapsed" },
        ) { (glassLayer, background, info, video, mini), constraints ->
            val p = progress()
            val videoBox = geometry.video(p, videoFills).let { page ->
                // Growing out of a card, the video fills the card's thumbnail at the start.
                sheet.origin?.let { lerp(page, Rect(Offset.Zero, it.bounds.size), sheet.launch) } ?: page
            }
            val window = Constraints.fixed(geometry.width.roundToInt(), geometry.height.roundToInt())
            val infoTop = geometry.pageVideo.bottom
            val glassPlaceables = glassLayer.map { it.measure(Constraints.fixed(constraints.maxWidth, constraints.maxHeight)) }
            val backgroundPlaceables = background.map { it.measure(window) }
            val infoPlaceables = info.map {
                it.measure(Constraints.fixed(geometry.width.roundToInt(), (geometry.height - infoTop).roundToInt().coerceAtLeast(0)))
            }
            val videoPlaceables = video.map {
                it.measure(Constraints.fixed(videoBox.width.roundToInt().coerceAtLeast(1), videoBox.height.roundToInt().coerceAtLeast(1)))
            }
            val miniPlaceables = mini.map {
                it.measure(Constraints.fixed(constraints.maxWidth, geometry.miniHeight.roundToInt().coerceAtMost(constraints.maxHeight)))
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                glassPlaceables.forEach { it.place(0, 0) }
                backgroundPlaceables.forEach { it.place(0, 0) }
                // Pushed a mini player's height further down as it fades, so it is clipped away (and
                // untouchable) by the time the sheet is the mini player.
                infoPlaceables.forEach { it.place(0, (videoBox.bottom + p * geometry.miniHeight).roundToInt()) }
                videoPlaceables.forEach { it.place(videoBox.left.roundToInt(), videoBox.top.roundToInt()) }
                miniPlaceables.forEach { it.place(0, 0) }
            }
        }
    }
}

// The page's own background gives way to the mini player's glass over the second half of the way.
private fun pageAlpha(p: Float) = 1f - ((p - 0.5f) / 0.45f).coerceIn(0f, 1f)

// Title, description and comments are gone within the first third, before the video gets small.
private fun pageContentAlpha(p: Float) = 1f - (p / 0.3f).coerceIn(0f, 1f)

// Growing out of a card, the page's text arrives over the second half of the way.
private fun launchContentAlpha(launch: Float) = 1f - (launch / 0.5f).coerceIn(0f, 1f)

// The mini player's own title and buttons arrive over the last stretch.
private fun miniAlpha(p: Float) = ((p - 0.7f) / 0.3f).coerceIn(0f, 1f)

/** Where things sit, in window px, at either end of the sheet; [container] and [video] interpolate. */
private class SheetGeometry(
    val width: Float,
    val height: Float,
    statusBar: Float,
    private val miniWidth: Float,
    miniBottom: Float,
    val miniHeight: Float,
    miniVideoStart: Float,
    miniVideoWidth: Float,
) {
    private val miniLeft = (width - miniWidth) / 2f
    val miniTop = height - miniBottom - miniHeight

    /** The portrait player under the status bar; the page continues below it. */
    val pageVideo = Rect(0f, statusBar, width, statusBar + width * 9f / 16f)
    private val fullVideo = Rect(0f, 0f, width, height)
    private val miniVideo = (miniVideoWidth * 9f / 16f).let { videoHeight ->
        val top = (miniHeight - videoHeight) / 2f
        Rect(miniVideoStart, top, miniVideoStart + miniVideoWidth, top + videoHeight)
    }

    // Sideways shrinking trails the drag, so the video stays big for the first part of it, as in YouTube.
    private fun shrink(p: Float) = p * p

    fun container(p: Float, videoFills: Boolean): Rect {
        val s = shrink(p)
        val left = lerp(0f, miniLeft, s)
        val top = lerp(0f, miniTop, p)
        val bottom = maxOf(lerp(height, miniTop + miniHeight, p), top + video(p, videoFills).bottom)
        return Rect(left, top, left + lerp(width, miniWidth, s), bottom)
    }

    /** The video's place inside the container. */
    fun video(p: Float, videoFills: Boolean): Rect = lerp(if (videoFills) fullVideo else pageVideo, miniVideo, shrink(p))
}

/**
 * Swipes on the full page's video: down drags the sheet toward the mini player, up enters fullscreen;
 * in fullscreen a swipe down leaves it and a swipe up brings up more videos. Ignored in mini player mode, where [miniPlayerDrags] takes over.
 */
private fun Modifier.pointerInputVideoDrag(
    sheet: PlayerSheetState,
    fullscreen: Boolean,
    inPictureInPicture: Boolean,
    toggleFullscreen: () -> Unit,
    onMoreVideos: () -> Unit,
): Modifier = pointerInput(sheet, fullscreen, inPictureInPicture) {
    if (inPictureInPicture) return@pointerInput
    val threshold = FullscreenSwipe.toPx()
    val tracker = VelocityTracker()
    awaitEachGesture {
        // The player's own tap detector consumes the down; drags still belong to us.
        val down = awaitFirstDown(requireUnconsumed = false)
        if (sheet.progress > 0.5f) return@awaitEachGesture
        var total = 0f
        val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            total = over
        } ?: return@awaitEachGesture
        tracker.resetTracking()
        tracker.addPosition(start.uptimeMillis, Offset(0f, total))
        // Down, or anything once the sheet has moved, drags the sheet; up on the full page means fullscreen.
        var moving = !fullscreen && total > 0f
        if (moving) sheet.dragBy(total)
        val completed = verticalDrag(start.id) { change ->
            val dy = change.positionChange().y
            change.consume()
            total += dy
            tracker.addPosition(change.uptimeMillis, Offset(0f, total))
            if (!fullscreen && (moving || total > 0f)) {
                moving = true
                sheet.dragBy(dy)
            }
        }
        when {
            moving -> sheet.settle(if (completed) tracker.calculateVelocity().y else 0f)
            fullscreen && total > threshold -> toggleFullscreen()
            fullscreen && total < -threshold -> onMoreVideos()
            !fullscreen && total < -threshold -> toggleFullscreen()
        }
    }
}

/** The mini player: up drags the sheet open, sideways swipes it away and stops the video. */
private suspend fun PointerInputScope.miniPlayerDrags(sheet: PlayerSheetState, onDismissed: () -> Unit) {
    val tracker = VelocityTracker()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // On the full page the video and the list handle their own drags.
        if (sheet.progress <= 0.5f) return@awaitEachGesture
        var total = Offset.Zero
        val start = awaitTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            total = over
        } ?: return@awaitEachGesture
        val sideways = abs(total.x) > abs(total.y)
        tracker.resetTracking()
        tracker.addPosition(start.uptimeMillis, total)
        if (sideways) sheet.dismissBy(total.x) else sheet.dragBy(total.y)
        val completed = drag(start.id) { change ->
            val delta = change.positionChange()
            change.consume()
            total += delta
            tracker.addPosition(change.uptimeMillis, total)
            if (sideways) sheet.dismissBy(delta.x) else sheet.dragBy(delta.y)
        }
        val velocity = if (completed) tracker.calculateVelocity() else Velocity.Zero
        if (sideways) sheet.settleDismiss(velocity.x, onDismissed) else sheet.settle(velocity.y)
    }
}

/**
 * Lets the page's list pull the sheet down once it is scrolled to the top, and push it back up before
 * scrolling again; the release settles the sheet like a drag on the video does.
 */
private fun sheetScrollConnection(sheet: PlayerSheetState) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y >= 0f || sheet.progress == 0f) return Offset.Zero
        val before = sheet.progress
        sheet.dragBy(available.y)
        return Offset(0f, (sheet.progress - before) * sheet.range)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.y <= 0f || !sheet.expanded) return Offset.Zero
        sheet.dragBy(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!sheet.dragging) return Velocity.Zero
        sheet.settle(available.y)
        return available
    }
}
