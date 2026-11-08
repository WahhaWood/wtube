package dev.wahha.wtube.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.rememberListReorder
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.DragHandle
import dev.wahha.wtube.ui.icon.SkipNext
import dev.wahha.wtube.ui.icon.WIcons

private val RowHeight = 72.dp

/** What plays after the current video: tap to play now, drag the handle to reorder, ✕ to drop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val nav = LocalNavigator.current
    val queue by PlayQueue.items.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val reorder = rememberListReorder(listState, queue, VideoItem::id) { moved, before -> PlayQueue.move(moved.id, before?.id) }

    fun play(video: VideoItem) {
        PlayQueue.remove(video.id)
        onDismiss()
        nav.openVideo(video.id)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.queue_count, queue.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (queue.isNotEmpty()) {
                TextButton(onClick = { queue.firstOrNull()?.let(::play) }) {
                    Icon(WIcons.SkipNext, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.queue_skip))
                }
                TextButton(onClick = {
                    PlayQueue.clear()
                    onDismiss()
                }) { Text(stringResource(R.string.queue_clear)) }
            }
        }
        LazyColumn(Modifier.heightIn(max = 560.dp), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
            items(reorder.rows, key = { it.id }) { video ->
                QueueRow(
                    video = video,
                    dragging = reorder.isDragged(video.id),
                    onClick = { play(video) },
                    onRemove = { PlayQueue.remove(video.id) },
                    modifier = reorder.row(this, video.id),
                    handle = reorder.handle(video.id),
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    video: VideoItem,
    dragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
    handle: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(RowHeight)
            .background(if (dragging) colors.surfaceContainerHigh else colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            WIcons.DragHandle,
            contentDescription = stringResource(R.string.queue_drag),
            tint = colors.outline,
            modifier = handle.padding(12.dp),
        )
        Thumbnail(video.thumbnail, Modifier.width(96.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f)) {
            Text(video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            video.channelName?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onRemove) {
            Icon(WIcons.Close, contentDescription = stringResource(R.string.queue_remove), tint = colors.outline)
        }
    }
}
