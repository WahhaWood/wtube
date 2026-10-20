package dev.wahha.wtube.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.CaptionTrack
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.Transcript
import dev.wahha.wtube.data.TranscriptLine
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.player.LocalPlayerState
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.ExpandMore
import dev.wahha.wtube.ui.icon.Search
import dev.wahha.wtube.ui.icon.WIcons
import kotlin.coroutines.cancellation.CancellationException

/**
 * The video's words with their times, from one of its caption tracks: the line being said is highlighted
 * and the list follows it until the user scrolls away; a tap seeks there. Search narrows to matching lines,
 * and a tap on one of them goes back to the whole text at that place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptSheet(videoId: String, tracks: List<CaptionTrack>, onSeek: (Long) -> Unit, onDismiss: () -> Unit) {
    val ordered = remember(tracks) { tracks.sortedBy { it.auto } }
    var track by remember(tracks) { mutableStateOf(Transcript.preferred(tracks, Settings.current.value.subtitleLanguage)) }
    var attempt by remember { mutableIntStateOf(0) }
    val result by produceState<Result<List<TranscriptLine>>?>(null, track, attempt) {
        value = null
        val chosen = track ?: return@produceState
        value = try {
            Result.success(Transcript.load(chosen))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val player = LocalPlayerState.current
    // Only while this video is the one playing: another one's position means nothing here.
    val position = if (player != null && player.mediaId == videoId) player.position else -1L

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ImmersiveDialog()
        Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.video_transcript), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            val current = track
            if (ordered.size > 1 && current != null) LanguageMenu(ordered, current) { track = it }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.transcript_search), style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(WIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(WIcons.Close, contentDescription = stringResource(R.string.close)) }
                }
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f)) {
            val loaded = result
            when {
                loaded == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                loaded.isFailure -> ErrorState(loaded.exceptionOrNull()!!, onRetry = { attempt++ }, modifier = Modifier.align(Alignment.Center))
                else -> TranscriptList(loaded.getOrThrow(), query.trim(), position) { line ->
                    onSeek(line.startMs)
                    query = ""
                }
            }
        }
    }
}

@Composable
private fun TranscriptList(lines: List<TranscriptLine>, query: String, positionMs: Long, onPick: (TranscriptLine) -> Unit) {
    val shown = remember(lines, query) { if (query.isEmpty()) lines else lines.filter { it.text.contains(query, ignoreCase = true) } }
    val searching = query.isNotEmpty()
    val current = if (positionMs < 0) -1 else shown.indexOfLast { it.startMs <= positionMs }
    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged) follow = false }
    LaunchedEffect(current, follow, searching) {
        // Two lines of context above the one being said.
        if (follow && !searching && current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        if (shown.isEmpty()) {
            Text(
                stringResource(if (searching) R.string.transcript_nothing_found else R.string.transcript_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.TopCenter).padding(24.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 56.dp),
        ) {
            itemsIndexed(shown) { index, line ->
                TranscriptRow(line, current = !searching && index == current) {
                    follow = true
                    onPick(line)
                }
            }
        }
        if (!follow && !searching && current >= 0) {
            FilledTonalButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp),
            ) {
                Text(stringResource(R.string.transcript_follow), color = LocalContentColor.current)
            }
        }
    }
}

@Composable
private fun TranscriptRow(line: TranscriptLine, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) colors.surfaceContainerHighest else colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            formatClock(line.startMs),
            style = MaterialTheme.typography.labelSmall,
            color = colors.primary,
            modifier = Modifier.width(52.dp).padding(top = 2.dp),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) colors.onSurface else colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The caption track the text comes from: human tracks first, then YouTube's automatic one. */
@Composable
private fun LanguageMenu(tracks: List<CaptionTrack>, selected: CaptionTrack, onPick: (CaptionTrack) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(trackLabel(selected), color = LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
            Icon(WIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tracks.forEach { track ->
                DropdownMenuItem(
                    text = { Text(trackLabel(track), color = if (track === selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        open = false
                        onPick(track)
                    },
                )
            }
        }
    }
}

@Composable
private fun trackLabel(track: CaptionTrack): String {
    val name = languageName(track.language) ?: track.language
    return if (track.auto) stringResource(R.string.player_subtitles_auto, name) else name
}
