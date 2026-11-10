package dev.wahha.wtube.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.ui.icon.PlayArrow
import dev.wahha.wtube.ui.icon.Shuffle
import dev.wahha.wtube.download.Download
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.download.arranged
import dev.wahha.wtube.download.waitingInOrder
import dev.wahha.wtube.ui.components.readableSide
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.ListReorder
import dev.wahha.wtube.ui.components.rememberListReorder
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.Delete
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.icon.DragHandle
import dev.wahha.wtube.ui.icon.Pause
import dev.wahha.wtube.ui.icon.Refresh
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import java.io.File

/** Everything saved or being saved: tap a finished one to watch it, also offline. What waits can be paused and put in order. */
@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val downloads by Downloads.entries.collectAsStateWithLifecycle()
    // A queue left over from a process the system killed resumes when this screen opens.
    LaunchedEffect(Unit) { Downloads.resume() }
    val listState = rememberLazyListState()
    val reorder = rememberListReorder(listState, remember(downloads) { downloads.waitingInOrder() }, Download::key) { moved, before ->
        Downloads.move(moved.key, before?.key)
    }
    // A drag freezes the run, not its contents; and a row that left it meanwhile must not be listed twice.
    val latest = remember(downloads) { downloads.associateBy { it.key } }
    val waiting = reorder.rows.map { latest[it.key] ?: it }
    val waitingKeys = waiting.mapTo(HashSet()) { it.key }
    val others = remember(downloads, waitingKeys) { downloads.arranged().filterNot { it.key in waitingKeys } }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = systemTop(),
            start = readableSide(),
            end = readableSide(),
            bottom = systemBottom() + LocalBottomInset.current,
        ),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (downloads.any { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED }) {
                    TextButton(onClick = Downloads::pauseAll) { Text(stringResource(R.string.downloads_pause_all)) }
                } else if (downloads.any { it.state == DownloadState.PAUSED }) {
                    TextButton(onClick = Downloads::unpauseAll) { Text(stringResource(R.string.downloads_continue_all)) }
                }
            }
        }
        if (downloads.isEmpty()) item { MessageState(WIcons.Download, stringResource(R.string.downloads_empty)) }
        // What is saved plays like a playlist, also offline: the first now, the rest through the queue.
        val saved = downloads.filter { it.state == DownloadState.DONE }.map { it.video }.distinctBy { it.id }
        if (saved.isNotEmpty()) item(key = "play") {
            fun play(shuffle: Boolean) {
                val videos = if (shuffle) saved.shuffled() else saved
                PlayQueue.replace(videos.drop(1))
                nav.openVideo(videos.first().id)
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { play(false) }, modifier = Modifier.weight(1f)) {
                    Icon(WIcons.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.playlist_play_all), color = LocalContentColor.current)
                }
                FilledTonalButton(onClick = { play(true) }, modifier = Modifier.weight(1f)) {
                    Icon(WIcons.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.playlist_shuffle), color = LocalContentColor.current)
                }
            }
        }
        items(others.filter { it.state == DownloadState.RUNNING }, key = { it.key }) { download ->
            Box(Modifier.animateItem()) { DownloadRow(download, onOpen = {}) }
        }
        items(waiting, key = { it.key }) { download ->
            DownloadRow(
                download,
                onOpen = {},
                modifier = reorder.row(this, download.key),
                reorder = reorder.takeIf { waiting.size > 1 },
            )
        }
        items(others.filter { it.state != DownloadState.RUNNING }, key = { it.key }) { download ->
            Box(Modifier.animateItem()) { DownloadRow(download, onOpen = { nav.openVideo(download.video.id) }) }
        }
    }
}

@Composable
private fun DownloadRow(download: Download, onOpen: () -> Unit, modifier: Modifier = Modifier, reorder: ListReorder<Download>? = null) {
    val dragging = reorder?.isDragged(download.key) == true
    val handle = reorder?.handle(download.key)
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val done = download.state == DownloadState.DONE
    val paused = download.state == DownloadState.PAUSED
    Row(
        modifier.fillMaxWidth()
            .then(if (dragging) Modifier.background(colors.surfaceContainerHigh) else Modifier)
            .clickable(enabled = done, onClick = onOpen)
            .padding(start = if (handle != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (handle != null) 4.dp else 12.dp),
    ) {
        if (handle != null) {
            Icon(WIcons.DragHandle, contentDescription = stringResource(R.string.queue_drag), tint = colors.outline, modifier = handle.padding(12.dp))
        }
        Thumbnail(download.video.thumbnail, Modifier.width(if (handle != null) 96.dp else 120.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f).padding(start = if (handle != null) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(download.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kind = if (download.audioOnly) stringResource(R.string.downloads_audio) else stringResource(R.string.downloads_quality, download.quality)
            val fetched = if (download.total > 0) "${Formatter.formatShortFileSize(context, download.bytes)} / ${Formatter.formatShortFileSize(context, download.total)}" else null
            val status = when (download.state) {
                DownloadState.DONE -> Formatter.formatShortFileSize(context, File(download.file).length())
                DownloadState.QUEUED -> stringResource(R.string.downloads_queued)
                DownloadState.RUNNING -> fetched ?: stringResource(R.string.downloads_running)
                DownloadState.PAUSED -> listOfNotNull(fetched, stringResource(R.string.downloads_on_pause)).joinToString(" · ")
                DownloadState.FAILED -> stringResource(R.string.downloads_failed)
            }
            Text("$kind · $status", style = MaterialTheme.typography.bodySmall, color = if (download.state == DownloadState.FAILED) colors.error else colors.onSurfaceVariant)
            if (download.state == DownloadState.RUNNING || paused && download.total > 0) {
                if (download.total > 0) {
                    LinearProgressIndicator(progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            download.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.outline, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        when (download.state) {
            DownloadState.FAILED -> IconButton(onClick = { Downloads.retry(download.key) }) { Icon(WIcons.Refresh, contentDescription = stringResource(R.string.retry)) }
            DownloadState.PAUSED -> IconButton(onClick = { Downloads.unpause(download.key) }) { Icon(WIcons.PlayArrow, contentDescription = stringResource(R.string.downloads_continue)) }
            DownloadState.QUEUED, DownloadState.RUNNING -> IconButton(onClick = { Downloads.pause(download.key) }) { Icon(WIcons.Pause, contentDescription = stringResource(R.string.downloads_pause)) }
            DownloadState.DONE -> {}
        }
        IconButton(onClick = { Downloads.remove(download.key) }) {
            Icon(WIcons.Delete, contentDescription = stringResource(R.string.downloads_delete), tint = colors.outline)
        }
    }
}
