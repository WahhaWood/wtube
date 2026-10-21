package dev.wahha.wtube.ui.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.Delete
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.icon.Refresh
import dev.wahha.wtube.ui.icon.WIcons
import java.io.File

/** Everything saved or being saved: tap a finished one to watch it, also offline. */
@Composable
fun DownloadsScreen() {
    val nav = LocalNavigator.current
    val downloads by Downloads.entries.collectAsStateWithLifecycle()
    // A queue left over from a process the system killed resumes when this screen opens.
    LaunchedEffect(Unit) { Downloads.resume() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalBottomInset.current,
        ),
    ) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.downloads_title), style = MaterialTheme.typography.headlineMedium)
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
        items(downloads, key = { it.key }) { download ->
            Box(Modifier.animateItem()) { DownloadRow(download, onOpen = { nav.openVideo(download.video.id) }) }
        }
    }
}

@Composable
private fun DownloadRow(download: Download, onOpen: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val done = download.state == DownloadState.DONE
    Row(
        Modifier.fillMaxWidth().clickable(enabled = done, onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Thumbnail(download.video.thumbnail, Modifier.width(120.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(download.video.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val kind = if (download.audioOnly) stringResource(R.string.downloads_audio) else stringResource(R.string.downloads_quality, download.quality)
            val status = when (download.state) {
                DownloadState.DONE -> Formatter.formatShortFileSize(context, File(download.file).length())
                DownloadState.QUEUED -> stringResource(R.string.downloads_queued)
                DownloadState.RUNNING -> if (download.total > 0) {
                    "${Formatter.formatShortFileSize(context, download.bytes)} / ${Formatter.formatShortFileSize(context, download.total)}"
                } else {
                    stringResource(R.string.downloads_running)
                }
                DownloadState.FAILED -> stringResource(R.string.downloads_failed)
            }
            Text("$kind · $status", style = MaterialTheme.typography.bodySmall, color = if (download.state == DownloadState.FAILED) colors.error else colors.onSurfaceVariant)
            if (download.state == DownloadState.RUNNING) {
                if (download.total > 0) {
                    LinearProgressIndicator(progress = { (download.bytes.toFloat() / download.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            download.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = colors.outline, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        if (download.state == DownloadState.FAILED) {
            IconButton(onClick = { Downloads.retry(download.key) }) { Icon(WIcons.Refresh, contentDescription = stringResource(R.string.retry)) }
        }
        IconButton(onClick = { Downloads.remove(download.key) }) {
            Icon(WIcons.Delete, contentDescription = stringResource(R.string.downloads_delete), tint = colors.outline)
        }
    }
}
