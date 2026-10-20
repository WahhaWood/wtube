package dev.wahha.wtube.ui.components

import dev.wahha.wtube.ui.video.ImmersiveDialog
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.PlaylistOption
import dev.wahha.wtube.ui.icon.Add
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// Account writes outlive the sheet or screen that started them: closing it must not cancel a like halfway.
private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Runs an account action in the background: on success shows [done] (with [arg]) if given; on failure
 * runs [onFailure] (to undo an optimistic change) and says what went wrong.
 */
fun launchAction(@StringRes done: Int? = null, arg: String? = null, onFailure: () -> Unit = {}, block: suspend () -> Unit) {
    actionScope.launch {
        try {
            block()
            done?.let { Notices.show(it, arg) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailure()
            Notices.failed(e)
        }
    }
}

/** "Subscribe", or "Subscribed" that asks before unsubscribing, as YouTube does. */
@Composable
fun SubscribeButton(subscribed: Boolean, channelName: String, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    // The theme's text styles carry their own colour, which would win over the button's content colour.
    if (subscribed) {
        FilledTonalButton(onClick = { confirm = true }, modifier = modifier) {
            Text(stringResource(R.string.video_subscribed), color = LocalContentColor.current)
        }
    } else {
        Button(onClick = { onChange(true) }, modifier = modifier) {
            Text(stringResource(R.string.video_subscribe), color = LocalContentColor.current)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(R.string.unsubscribe_title, channelName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    onChange(false)
                }) { Text(stringResource(R.string.unsubscribe_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "Save to…": the account's playlists with a tick where the video already is, and a new playlist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveSheet(videoId: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<PlaylistOption>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var creating by remember { mutableStateOf(false) }
    val watchLater = stringResource(R.string.library_watch_later)
    fun load() {
        error = null
        scope.launch {
            try {
                options = Actions.playlistOptions(videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
    }
    LaunchedEffect(videoId) { load() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ImmersiveDialog()
        Text(
            stringResource(R.string.save_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val list = options
        when {
            list != null -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.id }) { option ->
                    val title = if (option.id == Actions.WATCH_LATER) watchLater else option.title
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                val add = !option.contains
                                fun mark(contains: Boolean) {
                                    options = options?.map { if (it.id == option.id) it.copy(contains = contains) else it }
                                }
                                mark(add)
                                launchAction(if (add) R.string.saved_to else R.string.removed_from, title, onFailure = { mark(!add) }) {
                                    Actions.setInPlaylist(option.id, videoId, add)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = option.contains, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                }
            }
            error != null -> ErrorState(error!!, ::load, Modifier.fillMaxWidth().padding(vertical = 24.dp))
            else -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        Row(
            Modifier.fillMaxWidth().clickable { creating = true }.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(WIcons.Add, contentDescription = null)
            Text(stringResource(R.string.playlist_new), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(12.dp))
    }
    if (creating) {
        CreatePlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { title, privacy ->
                creating = false
                onDismiss()
                launchAction(R.string.playlist_created, title) { Actions.createPlaylist(title, privacy, videoId) }
            },
        )
    }
}

private val Privacies = listOf(
    "PRIVATE" to R.string.playlist_private,
    "UNLISTED" to R.string.playlist_unlisted,
    "PUBLIC" to R.string.playlist_public,
)

@Composable
private fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (title: String, privacy: String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(Privacies.first().first) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_new)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Privacies.forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clickable { privacy = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = privacy == value, onClick = null, modifier = Modifier.padding(12.dp))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim(), privacy) }, enabled = title.isNotBlank()) { Text(stringResource(R.string.playlist_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** A dialog asking for one piece of text, such as a reply to a comment. */
@Composable
fun TextEntryDialog(title: String, placeholder: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
