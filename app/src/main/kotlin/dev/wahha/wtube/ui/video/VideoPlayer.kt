package dev.wahha.wtube.ui.video

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import dev.wahha.wtube.data.Chapter
import dev.wahha.wtube.ui.icon.ClosedCaption
import dev.wahha.wtube.ui.icon.SkipNext
import dev.wahha.wtube.ui.icon.SkipPrevious
import androidx.compose.foundation.Canvas
import dev.wahha.wtube.data.Segment
import dev.wahha.wtube.data.SponsorBlock
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dev.wahha.wtube.R
import dev.wahha.wtube.ui.glass.CapsuleShape
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.player.Autoplay
import dev.wahha.wtube.player.LocalPlayerConnection
import dev.wahha.wtube.player.PlayerState
import dev.wahha.wtube.ui.components.errorMessage
import dev.wahha.wtube.ui.icon.ExpandMore
import dev.wahha.wtube.ui.icon.FastForward
import dev.wahha.wtube.ui.icon.Fullscreen
import dev.wahha.wtube.ui.icon.FullscreenExit
import dev.wahha.wtube.ui.icon.Pause
import dev.wahha.wtube.ui.icon.PlayArrow
import dev.wahha.wtube.ui.icon.Replay
import dev.wahha.wtube.ui.icon.Settings
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.stream.Frameset
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HIDE_DELAY_MS = 3_000L
private const val BURST_WINDOW_MS = 800L
private const val HINT_MS = 1_200L

/** A finger drag across the whole width seeks this far (or the whole video, if shorter). */
private const val SEEK_DRAG_SPAN_MS = 120_000L
// The player plays a stream some 15 s behind its newest segment; within this it counts as live.
private const val LIVE_EDGE_MS = 30_000L
private const val BOOST_SPEED = 2f

/** Consecutive double taps on one side add up: "+10 с", "+20 с"... */
private class SeekBurst(val forward: Boolean, val seconds: Int, val key: Int)

private enum class DragMode { Seek, Pinch }

/**
 * The video surface with our own controls, after the YouTube app: gradients top and bottom instead of a
 * flat dim, round buttons in the middle, a hairline of progress while the controls are hidden.
 *
 * Gestures: tap toggles the controls, double tap on either half seeks by the step from Settings, a long
 * press plays at 2x while held, a horizontal drag seeks with a storyboard preview, and in fullscreen a pinch
 * switches fit / fill. Vertical drags are left to the sheet (collapse, fullscreen) in [PlayerSheet]; no
 * volume or brightness swipes, the user found them getting in the way.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    player: Player,
    state: PlayerState,
    title: String?,
    channel: String?,
    // Shown until the first frame, so the mini player is never a black box while loading.
    thumbnail: String?,
    storyboard: List<Frameset>,
    chapters: List<Chapter>,
    // "Most replayed": intensities of equal slices of the video, drawn over the seek bar while seeking.
    heatmap: List<Float>,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onCollapse: () -> Unit,
    onRetry: () -> Unit,
    onPrevious: () -> Unit,
    // Null when there is nothing to play next.
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    // Likes, comments and the rest under the seek bar, shown only in fullscreen as in YouTube.
    fullscreenActions: @Composable () -> Unit = {},
    // Off in picture-in-picture: the window is too small to touch, the system draws its own buttons.
    showControls: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val settings by Settings.current.collectAsStateWithLifecycle()
    val upcoming by Autoplay.upcoming.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on every interaction, restarting the hide timer.
    var interaction by remember { mutableIntStateOf(0) }
    var burst by remember { mutableStateOf<SeekBurst?>(null) }
    // Seek position (0..1) while a finger is on the seek bar or dragging across the video; null otherwise.
    var scrub by remember { mutableStateOf<Float?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    var fill by remember { mutableStateOf(false) }
    // A short message in the middle (zoom, autoplay), as a string resource; 0 when none.
    var hint by remember { mutableIntStateOf(0) }
    val ended = state.playbackState == Player.STATE_ENDED
    val buffering = state.playbackState == Player.STATE_BUFFERING ||
        (state.playbackState == Player.STATE_IDLE && state.playWhenReady && state.error == null)
    val audioOnly = C.TRACK_TYPE_VIDEO in state.parameters.disabledTrackTypes
    val step = settings.doubleTapSeconds

    LaunchedEffect(controlsVisible, interaction, state.isPlaying, scrub, settingsOpen) {
        if (controlsVisible && state.isPlaying && scrub == null && !settingsOpen) {
            delay(HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(burst?.key) {
        if (burst != null) {
            delay(BURST_WINDOW_MS)
            burst = null
        }
    }
    LaunchedEffect(hint) {
        if (hint != 0) {
            delay(HINT_MS)
            hint = 0
        }
    }
    // The fill zoom only lasts while fullscreen.
    DisposableEffect(fullscreen) {
        onDispose { fill = false }
    }

    val description = when {
        state.error != null -> "error"
        ended -> "ended"
        buffering -> "buffering"
        state.isPlaying -> "playing"
        else -> "paused"
    }
    BoxWithConstraints(
        modifier.background(colors.scrim).testTag("video_player").semantics { stateDescription = description },
    ) {
        val width = constraints.maxWidth
        // Shrunk into the mini player there is no room for a spinner, captions or an error message.
        val roomy = maxWidth > 200.dp
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            // A TextureView, unlike a SurfaceView, follows the sheet's clipping, resizing and rounded corners.
            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
            contentScale = if (fill && fullscreen) ContentScale.Crop else ContentScale.Fit,
            shutter = {
                Box(Modifier.fillMaxSize().background(colors.scrim)) {
                    AsyncImage(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            },
        )
        // With the video track off the surface would keep its last frame.
        if (audioOnly) {
            AsyncImage(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (roomy) SubtitleOverlay(state.cues, settings, raised = showControls && controlsVisible)

        fun seekBy(forward: Boolean) {
            val target = player.currentPosition + (if (forward) step else -step) * 1000L
            val duration = player.duration
            player.seekTo(if (duration > 0) target.coerceIn(0, duration) else target.coerceAtLeast(0))
        }

        if (showControls) {
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onPress = {
                                tryAwaitRelease()
                                if (boosting) {
                                    boosting = false
                                    player.setPlaybackSpeed(Settings.current.value.speed)
                                }
                            },
                            onLongPress = {
                                if (player.isPlaying) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    boosting = true
                                    controlsVisible = false
                                    player.setPlaybackSpeed(BOOST_SPEED)
                                }
                            },
                            onTap = {
                                controlsVisible = !controlsVisible
                                interaction++
                            },
                            onDoubleTap = { offset ->
                                val forward = offset.x > width / 2
                                seekBy(forward)
                                val previous = burst?.takeIf { it.forward == forward }
                                burst = SeekBurst(forward, (previous?.seconds ?: 0) + Settings.current.value.doubleTapSeconds, (burst?.key ?: 0) + 1)
                                controlsVisible = false
                            },
                        )
                    }
                    .pointerInput(player, fullscreen) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var total = Offset.Zero
                            var mode: DragMode? = null
                            // Decide whose drag it is once it passes the touch slop; vertical drags stay
                            // unconsumed for the sheet.
                            while (mode == null) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    if (!fullscreen) return@awaitEachGesture
                                    mode = DragMode.Pinch
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                                if (!change.pressed || change.isConsumed || boosting) return@awaitEachGesture
                                total += change.positionChange()
                                if (total.getDistance() > viewConfiguration.touchSlop) {
                                    val horizontal = abs(total.x) > abs(total.y)
                                    mode = when {
                                        horizontal && state.duration > 0 && player.isCurrentMediaItemSeekable -> DragMode.Seek
                                        else -> return@awaitEachGesture
                                    }
                                    change.consume()
                                }
                            }
                            when (mode) {
                                DragMode.Seek -> {
                                    val duration = state.duration
                                    val span = minOf(duration, SEEK_DRAG_SPAN_MS).toFloat() / duration
                                    var fraction = (player.currentPosition.toFloat() / duration + total.x / size.width * span).coerceIn(0f, 1f)
                                    scrub = fraction
                                    controlsVisible = true
                                    val completed = drag(down.id) { change ->
                                        fraction = (fraction + change.positionChange().x / size.width * span).coerceIn(0f, 1f)
                                        scrub = fraction
                                        change.consume()
                                    }
                                    if (completed) player.seekTo((fraction * duration).toLong())
                                    scrub = null
                                    interaction++
                                }
                                DragMode.Pinch -> {
                                    var zoom = 1f
                                    do {
                                        val event = awaitPointerEvent()
                                        zoom *= event.calculateZoom()
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } while (event.changes.any { it.pressed })
                                    if (zoom > 1.15f && !fill) {
                                        fill = true
                                        hint = R.string.player_zoom_fill
                                    } else if (zoom < 0.87f && fill) {
                                        fill = false
                                        hint = R.string.player_zoom_fit
                                    }
                                }
                                null -> Unit
                            }
                        }
                    },
            )
        }

        burst?.let { SeekBurstOverlay(it) }

        AnimatedVisibility(
            visible = showControls && (controlsVisible || ended || scrub != null),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Controls(
                player = player,
                state = state,
                title = title,
                channel = channel,
                chapters = chapters,
                heatmap = heatmap,
                fullscreen = fullscreen,
                ended = ended,
                buffering = buffering,
                scrub = scrub,
                onPrevious = onPrevious,
                onNext = onNext,
                onScrub = {
                    scrub = it
                    interaction++
                },
                onToggleFullscreen = onToggleFullscreen,
                onCollapse = onCollapse,
                onSettings = { settingsOpen = true },
                onHint = { hint = it },
                onInteraction = { interaction++ },
                fullscreenActions = fullscreenActions,
            )
        }

        // While the controls are hidden a hairline keeps the progress in sight, as in YouTube.
        if (showControls && !controlsVisible && !fullscreen && state.duration > 0) {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth((state.position.toFloat() / state.duration).coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(colors.primary),
            )
        }

        val density = LocalDensity.current
        val seeking = scrub
        if (showControls && seeking != null && storyboard.isNotEmpty()) {
            val previewWidth = with(density) { PreviewWidth.toPx() }
            val previewMs = (seeking * state.duration).toLong()
            SeekPreview(
                storyboard,
                previewMs,
                chapterAt(chapters, previewMs)?.title,
                Modifier.align(Alignment.BottomStart)
                    .padding(bottom = if (fullscreen) 150.dp else 60.dp)
                    .offset { IntOffset((seeking * width - previewWidth / 2).coerceIn(0f, (width - previewWidth).coerceAtLeast(0f)).roundToInt(), 0) },
            )
        }

        if (buffering && roomy && (!controlsVisible || !showControls)) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(44.dp), color = colors.onSurface, strokeWidth = 3.dp)
        }

        if (showControls) {
            when {
                boosting -> GestureBadge(WIcons.FastForward, "2x", Modifier.align(Alignment.TopCenter).padding(top = 16.dp))
                hint != 0 -> GestureBadge(null, stringResource(hint), Modifier.align(Alignment.Center))
            }
        }

        if (roomy && showControls) upcoming?.takeIf { ended }?.let { next ->
            UpNextOverlay(
                next,
                onPlay = Autoplay::playNow,
                onCancel = Autoplay::cancel,
                modifier = Modifier.fillMaxSize().padding(top = if (fullscreen) 64.dp else 48.dp),
            )
        }

        if (roomy && statsOpen) {
            StatsOverlay(
                LocalPlayerConnection.current,
                onClose = { statsOpen = false },
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 48.dp).fillMaxWidth(0.75f),
            )
        }

        if (roomy) state.error?.let { error ->
            Column(
                Modifier.fillMaxSize().background(colors.scrim.copy(alpha = 0.85f)).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val cause = error.cause
                Text(
                    stringResource(if (cause != null) errorMessage(cause) else R.string.error_playback),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
    }

    if (settingsOpen) PlayerSettingsSheet(player, state, onStats = { statsOpen = true }, onDismiss = { settingsOpen = false })
}

private val PreviewWidth = 160.dp

@Composable
private fun Controls(
    player: Player,
    state: PlayerState,
    title: String?,
    channel: String?,
    chapters: List<Chapter>,
    heatmap: List<Float>,
    fullscreen: Boolean,
    ended: Boolean,
    buffering: Boolean,
    scrub: Float?,
    onPrevious: () -> Unit,
    onNext: (() -> Unit)?,
    onScrub: (Float?) -> Unit,
    onToggleFullscreen: () -> Unit,
    onCollapse: () -> Unit,
    onSettings: () -> Unit,
    onHint: (Int) -> Unit,
    onInteraction: () -> Unit,
    fullscreenActions: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val scrim = colors.scrim
    val settings by Settings.current.collectAsStateWithLifecycle()
    // Fullscreen hides the system bars, but a camera cutout still eats into the edge.
    val edges = if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = 12.dp) else Modifier
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(scrim.copy(alpha = 0.25f)))
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(if (fullscreen) 120.dp else 80.dp)
                .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0.7f), scrim.copy(alpha = 0f)))),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (fullscreen) 200.dp else 96.dp)
                .background(Brush.verticalGradient(listOf(scrim.copy(alpha = 0f), scrim.copy(alpha = 0.8f)))),
        )

        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().then(edges).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    WIcons.ExpandMore,
                    contentDescription = stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_collapse),
                    tint = colors.onSurface,
                )
            }
            // Title and channel only in fullscreen: on the page they are right below the player.
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (fullscreen) {
                    Text(
                        title.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!channel.isNullOrEmpty()) {
                        Text(
                            channel,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            AutoplaySwitch(settings.autoplay) {
                Settings.set(Settings.Autoplay, it)
                onHint(if (it) R.string.player_autoplay_on else R.string.player_autoplay_off)
                onInteraction()
            }
            if (state.tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                val captionsOn = C.TRACK_TYPE_TEXT !in state.parameters.disabledTrackTypes
                IconButton(onClick = {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, captionsOn)
                        .apply { if (captionsOn) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
                        .build()
                    Settings.set(Settings.Subtitles, !captionsOn)
                    onInteraction()
                }) {
                    Icon(
                        WIcons.ClosedCaption,
                        contentDescription = stringResource(R.string.player_subtitles),
                        tint = if (captionsOn) colors.onSurface else colors.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.semantics { stateDescription = if (captionsOn) "on" else "off" },
                    )
                }
            }
            IconButton(onClick = onSettings) {
                Icon(WIcons.Settings, contentDescription = stringResource(R.string.player_settings), tint = colors.onSurface)
            }
        }

        Row(
            Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 64.dp else 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(stringResource(R.string.player_previous), 48.dp, onClick = {
                onPrevious()
                onInteraction()
            }) {
                Icon(WIcons.SkipPrevious, contentDescription = null, modifier = Modifier.size(30.dp), tint = colors.onSurface)
            }
            Box(Modifier.size(68.dp), contentAlignment = Alignment.Center) {
                if (buffering) {
                    CircularProgressIndicator(Modifier.size(52.dp), color = colors.onSurface, strokeWidth = 3.dp)
                } else {
                    val (icon, label) = when {
                        ended -> WIcons.Replay to R.string.player_replay
                        state.playWhenReady -> WIcons.Pause to R.string.player_pause
                        else -> WIcons.PlayArrow to R.string.player_play
                    }
                    RoundButton(
                        stringResource(label),
                        68.dp,
                        Modifier.testTag("player_play_pause"),
                        onClick = {
                            when {
                                ended -> player.seekTo(0).also { player.play() }
                                state.playWhenReady -> player.pause()
                                else -> player.play()
                            }
                            onInteraction()
                        },
                    ) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = colors.onSurface)
                    }
                }
            }
            RoundButton(stringResource(R.string.player_next), 48.dp, enabled = onNext != null, onClick = {
                onNext?.invoke()
                onInteraction()
            }) {
                Icon(
                    WIcons.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = if (onNext != null) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(edges)
                .padding(start = 16.dp, end = 4.dp, bottom = if (fullscreen) 12.dp else 0.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val shown = scrub?.let { (it * state.duration).toLong() } ?: state.position
                if (state.live) {
                    LiveBadge(behindMs = state.duration - shown, onGoLive = {
                        player.seekToDefaultPosition()
                        onInteraction()
                    })
                } else {
                    Text(formatClock(shown), style = MaterialTheme.typography.labelSmall, color = colors.onSurface)
                    Text(
                        " / " + formatClock(state.duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface.copy(alpha = 0.7f),
                    )
                }
                Text(
                    chapterAt(chapters, shown)?.let { stringResource(R.string.meta_separator) + it.title }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFullscreen) {
                    Icon(
                        if (fullscreen) WIcons.FullscreenExit else WIcons.Fullscreen,
                        contentDescription = stringResource(if (fullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen),
                        tint = colors.onSurface,
                    )
                }
            }
            val segments by SponsorBlock.current.collectAsStateWithLifecycle()
            SeekBar(
                position = state.position,
                buffered = state.buffered,
                duration = state.duration,
                chapters = chapters,
                heatmap = heatmap,
                segments = segments?.takeIf { it.videoId == state.mediaId }?.segments.orEmpty(),
                scrub = scrub,
                onScrub = onScrub,
                onSeek = { player.seekTo(it) },
                modifier = Modifier.padding(end = 12.dp),
            )
            if (fullscreen) fullscreenActions()
        }
    }
}

/**
 * "Live" for a stream, as in YouTube: the dot is red at the live edge; behind it, how far behind, and a
 * tap jumps back to the edge.
 */
@Composable
private fun LiveBadge(behindMs: Long, onGoLive: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val atEdge = behindMs < LIVE_EDGE_MS
    Row(
        Modifier.clip(CapsuleShape).clickable(enabled = !atEdge, onClickLabel = stringResource(R.string.player_go_live), onClick = onGoLive)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (atEdge) colors.error else colors.outline))
        Text(stringResource(R.string.live), style = MaterialTheme.typography.labelSmall, color = colors.onSurface)
        if (!atEdge) {
            Text("−" + formatClock(behindMs), style = MaterialTheme.typography.bodySmall, color = colors.onSurface.copy(alpha = 0.7f))
        }
    }
}

/** The chapter playing at [positionMs], if the video has chapters. */
internal fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? =
    chapters.lastOrNull { it.startSeconds * 1000L <= positionMs }

/** YouTube's autoplay toggle: a small switch with a play or pause sign on its knob. */
@Composable
private fun AutoplaySwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val knob by animateDpAsState(if (on) 16.dp else 0.dp, label = "autoplay")
    val label = stringResource(R.string.player_autoplay)
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable { onChange(!on) }.semantics {
            contentDescription = label
            stateDescription = if (on) "on" else "off"
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 20.dp)) {
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth().height(14.dp).clip(CircleShape)
                    .background(if (on) colors.onSurface.copy(alpha = 0.5f) else colors.onSurface.copy(alpha = 0.3f)),
            )
            Box(
                Modifier.offset(x = knob).size(20.dp).clip(CircleShape).background(if (on) colors.onSurface else colors.onSurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (on) WIcons.PlayArrow else WIcons.Pause, contentDescription = null, modifier = Modifier.size(14.dp), tint = colors.scrim)
            }
        }
    }
}

@Composable
private fun RoundButton(
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** The half-moon flash on the tapped side, YouTube style, with the seconds skipped so far. */
@Composable
private fun BoxScope.SeekBurstOverlay(burst: SeekBurst) {
    val colors = MaterialTheme.colorScheme
    val shape = if (burst.forward) {
        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    } else {
        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    }
    Box(
        Modifier
            .align(if (burst.forward) Alignment.CenterEnd else Alignment.CenterStart)
            .fillMaxHeight()
            .fillMaxWidth(0.38f)
            .clip(shape)
            // Dark, so it reads on bright frames too; the white glyphs carry it on dark ones.
            .background(colors.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SeekIcon(forward = burst.forward, seconds = Settings.current.value.doubleTapSeconds, size = 24.dp)
            Text(
                (if (burst.forward) "+" else "−") + stringResource(R.string.player_seek_amount, burst.seconds),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface,
            )
        }
    }
}

/**
 * Rounded track that thickens under the finger; drags preview through [onScrub], release seeks. With
 * chapters the track breaks into pieces at their starts, as in YouTube; while the finger is on it the
 * "Most replayed" graph rises above it.
 */
@Composable
private fun SeekBar(
    position: Long,
    buffered: Long,
    duration: Long,
    chapters: List<Chapter>,
    heatmap: List<Float>,
    segments: List<Segment>,
    scrub: Float?,
    onScrub: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var widthPx by remember { mutableFloatStateOf(1f) }
    val fraction = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val track by animateDpAsState(if (scrub != null) 6.dp else 4.dp, label = "track")
    val thumb by animateDpAsState(if (scrub != null) 18.dp else 13.dp, label = "thumb")
    val graph by animateFloatAsState(if (scrub != null && heatmap.size > 1) 1f else 0f, label = "heatmap")
    val density = LocalDensity.current

    Box(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat() }
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / size.width * duration).toLong()) }
            }
            .pointerInput(duration) {
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        last = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                    onDragEnd = {
                        if (duration > 0) onSeek((last * duration).toLong())
                        onScrub(null)
                    },
                    onDragCancel = { onScrub(null) },
                    onHorizontalDrag = { change, _ ->
                        last = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(last)
                    },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val rest = colors.onSurface.copy(alpha = 0.22f)
        val loaded = colors.onSurface.copy(alpha = 0.4f)
        val played = colors.primary
        val mark = colors.tertiary
        val heat = colors.onSurface
        Canvas(Modifier.fillMaxWidth().height(track)) {
            if (graph > 0f) drawHeatmap(heatmap, heat.copy(alpha = 0.35f * graph), graph)
            val gap = 2.dp.toPx()
            val radius = CornerRadius(size.height / 2f)
            // Chapter starts as x positions; the first chapter starts at 0 and makes no break.
            val breaks = if (duration > 0) {
                chapters.map { size.width * it.startSeconds * 1000f / duration }.filter { it > gap && it < size.width - gap }
            } else {
                emptyList()
            }
            val edges = listOf(0f) + breaks + size.width
            fun piece(color: Color, from: Float, to: Float, upTo: Float) {
                val end = minOf(to, upTo)
                if (end > from) drawRoundRect(color, Offset(from, 0f), Size(end - from, size.height), radius)
            }
            for (i in 0 until edges.size - 1) {
                val from = edges[i] + if (i > 0) gap / 2f else 0f
                val to = edges[i + 1] - if (i < edges.size - 2) gap / 2f else 0f
                piece(rest, from, to, to)
                piece(loaded, from, to, size.width * bufferedFraction)
                piece(played, from, to, size.width * fraction)
            }
            // SponsorBlock segments, over the progress so they stay visible behind the playhead.
            if (duration > 0) {
                for (segment in segments) {
                    val start = size.width * segment.startMs / duration
                    val end = size.width * segment.endMs.coerceAtMost(duration) / duration
                    drawRect(mark, topLeft = Offset(start, 0f), size = Size((end - start).coerceAtLeast(2f), size.height))
                }
            }
        }
        Box(
            Modifier
                .offset(x = with(density) { (widthPx * fraction).toDp() } - thumb / 2)
                .size(thumb)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

/**
 * The "Most replayed" graph right above the track: a smooth curve through the middle of each slice, from
 * [HeatmapMin] for the least replayed to [HeatmapMax] for the most, grown by [grow] as it appears.
 */
private fun DrawScope.drawHeatmap(heatmap: List<Float>, color: Color, grow: Float) {
    val base = -HeatmapGap.toPx()
    val low = HeatmapMin.toPx()
    val high = HeatmapMax.toPx()
    val step = size.width / heatmap.size
    val points = heatmap.mapIndexed { i, v -> Offset(step * (i + 0.5f), base - (low + (high - low) * v) * grow) }
    val path = Path().apply {
        moveTo(0f, base)
        lineTo(0f, points.first().y)
        lineTo(points.first().x, points.first().y)
        // Through the midpoints, each slice's own point as the control: no overshoot between slices.
        for (i in 1 until points.size) {
            val from = points[i - 1]
            val to = points[i]
            quadraticTo(from.x, from.y, (from.x + to.x) / 2f, (from.y + to.y) / 2f)
        }
        lineTo(points.last().x, points.last().y)
        lineTo(size.width, points.last().y)
        lineTo(size.width, base)
        close()
    }
    drawPath(path, color)
}

private val HeatmapGap = 6.dp
private val HeatmapMin = 2.dp
private val HeatmapMax = 30.dp
