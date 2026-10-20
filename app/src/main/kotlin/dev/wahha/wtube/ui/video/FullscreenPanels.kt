package dev.wahha.wtube.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.formatCompact
import dev.wahha.wtube.ui.components.SaveSheet
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.VideoBadge
import dev.wahha.wtube.ui.components.shareVideo
import dev.wahha.wtube.ui.components.videoMeta
import dev.wahha.wtube.ui.icon.Comment
import dev.wahha.wtube.ui.icon.PlaylistAdd
import dev.wahha.wtube.ui.icon.Share
import dev.wahha.wtube.ui.icon.ThumbDown
import dev.wahha.wtube.ui.icon.ThumbUp
import dev.wahha.wtube.ui.icon.VideoLibrary
import dev.wahha.wtube.ui.icon.WIcons

/** What slides over the fullscreen video: comments on the right, or a shelf of more videos at the bottom. */
enum class FullscreenPanel { Comments, MoreVideos }

private val PanelShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
private val UprightPanelShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
private val ShelfCardWidth = 220.dp

/** The row under the seek bar in fullscreen, as in YouTube: rating, comments, saving, sharing, more videos. */
@Composable
internal fun FullscreenActions(vm: VideoViewModel, onPanel: (FullscreenPanel) -> Unit) {
    val details = vm.details ?: return
    val video = details.item
    val state = vm.state
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var saving by remember { mutableStateOf(false) }
    if (saving) SaveSheet(video.id, onDismiss = { saving = false })
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(end = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state != null) {
            RatingPill(state.like, vm.likes, vm.dislikesShown) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                vm.rate(it)
            }
        } else {
            if (details.likes >= 0) ActionPill(WIcons.ThumbUp, formatCompact(details.likes), onClick = null)
            if (vm.dislikes >= 0) ActionPill(WIcons.ThumbDown, formatCompact(vm.dislikes), onClick = null)
        }
        ActionPill(WIcons.Comment, stringResource(R.string.video_comments)) { onPanel(FullscreenPanel.Comments) }
        if (state != null) ActionPill(WIcons.PlaylistAdd, stringResource(R.string.video_save)) { saving = true }
        ActionPill(WIcons.Share, stringResource(R.string.video_share)) { shareVideo(context, video) }
        if (details.related.any { it is VideoItem }) {
            ActionPill(WIcons.VideoLibrary, stringResource(R.string.player_more_videos)) { onPanel(FullscreenPanel.MoreVideos) }
        }
    }
}

/** The open [panel] over the fullscreen video; [onPanel] with null closes it. */
@Composable
internal fun FullscreenPanels(vm: VideoViewModel, panel: FullscreenPanel?, onPanel: (FullscreenPanel?) -> Unit, onSeek: (Long) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A vertical video is fullscreen upright: there the comments come up from the bottom instead.
        val upright = maxWidth < maxHeight
        AnimatedVisibility(
            visible = panel == FullscreenPanel.MoreVideos,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
        ) {
            MoreVideosShelf(vm, onClose = { onPanel(null) })
        }
        AnimatedVisibility(
            visible = panel == FullscreenPanel.Comments,
            enter = if (upright) slideInVertically { it } else slideInHorizontally { it },
            exit = if (upright) slideOutVertically { it } else slideOutHorizontally { it },
            modifier = Modifier.align(if (upright) Alignment.BottomCenter else Alignment.CenterEnd),
        ) {
            CommentsPanel(
                vm,
                onSeek,
                onClose = { onPanel(null) },
                modifier = if (upright) {
                    Modifier.fillMaxWidth().fillMaxHeight(0.65f).clip(UprightPanelShape)
                } else {
                    Modifier.fillMaxHeight().fillMaxWidth(0.45f).clip(PanelShape)
                },
            )
        }
    }
}

/** Comments over the fullscreen video, which keeps playing beside or above them. */
@Composable
private fun CommentsPanel(vm: VideoViewModel, onSeek: (Long) -> Unit, onClose: () -> Unit, modifier: Modifier) {
    val comments = vm.comments ?: return
    val onLink = rememberLinkHandler(vm.id.orEmpty(), onSeek)
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .background(colors.surfaceContainerLow)
            // A touch target of its own, so touches on the panel never reach the video under it.
            .pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))
            .padding(top = 8.dp),
    ) {
        CommentsContent(vm, comments, onLink, listModifier = Modifier.weight(1f), onClose = onClose)
    }
}

/** YouTube's "more videos" shelf: related videos in a row at the bottom; a tap or a swipe down elsewhere closes it. */
@Composable
private fun MoreVideosShelf(vm: VideoViewModel, onClose: () -> Unit) {
    val videos = vm.details?.related.orEmpty().filterIsInstance<VideoItem>()
    val colors = MaterialTheme.colorScheme
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.scrim.copy(alpha = 0.3f), colors.scrim.copy(alpha = 0.9f))))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total > threshold) onClose() },
                ) { change, dy ->
                    change.consume()
                    total += dy
                }
            },
    ) {
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.player_more_videos),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(videos, key = { it.id }) { video ->
                    Column(
                        Modifier.width(ShelfCardWidth).clip(RoundedCornerShape(12.dp)).clickable {
                            onClose()
                            vm.open(video.id)
                        },
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Thumbnail(video.thumbnail, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) { VideoBadge(video) }
                        Text(
                            video.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            videoMeta(video, withChannel = true),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
