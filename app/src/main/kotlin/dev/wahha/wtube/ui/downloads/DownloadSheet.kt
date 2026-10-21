package dev.wahha.wtube.ui.downloads

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.download.DownloadOption
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.download.downloadOptions
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.errorMessage
import dev.wahha.wtube.ui.icon.Audiotrack
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.icon.WIcons

/** What to save of [video]: each H.264 quality with its size, or the sound alone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(video: VideoItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var options by remember { mutableStateOf<List<DownloadOption>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(video.id) {
        runCatching { downloadOptions(YouTube.streamInfo(video.id)) }.onSuccess { options = it }.onFailure { error = it }
    }
    var picked by remember { mutableStateOf<DownloadOption?>(null) }
    fun start(option: DownloadOption) {
        Downloads.enqueue(video, option.audioOnly, option.height)
        Notices.show(R.string.downloads_started)
        onDismiss()
    }
    // The progress notification needs the permission on Android 13+; the download runs either way.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { picked?.let(::start) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.downloads_pick), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val list = options
            when {
                error != null -> Text(stringResource(errorMessage(error!!)), modifier = Modifier.padding(24.dp))
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(stringResource(R.string.downloads_unavailable), modifier = Modifier.padding(24.dp))
                else -> list.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                picked = option
                                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                start(option)
                            }
                        }.padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(if (option.audioOnly) WIcons.Audiotrack else WIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (option.audioOnly) stringResource(R.string.downloads_audio) else stringResource(R.string.downloads_quality, option.height),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(Formatter.formatShortFileSize(context, option.bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Qualities a whole list is saved at: each video gets the best it has up to the pick. */
private val ListQualities = listOf(1080, 720, 480, 360)

/** What to save of a whole playlist: a quality cap for every video, or the sound alone. [onPick] gets (audioOnly, height). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDownloadSheet(onPick: (Boolean, Int) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }
    fun start(choice: Pair<Boolean, Int>) {
        onPick(choice.first, choice.second)
        onDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { picked?.let(::start) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.downloads_playlist), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            Text(
                stringResource(R.string.downloads_playlist_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            (ListQualities.map { false to it } + (true to 0)).forEach { choice ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            picked = choice
                            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            start(choice)
                        }
                    }.padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(if (choice.first) WIcons.Audiotrack else WIcons.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (choice.first) stringResource(R.string.downloads_audio) else stringResource(R.string.downloads_up_to, choice.second),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
