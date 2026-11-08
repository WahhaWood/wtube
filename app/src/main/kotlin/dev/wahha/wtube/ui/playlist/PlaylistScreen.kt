package dev.wahha.wtube.ui.playlist

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.runtime.remember
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import dev.wahha.wtube.ui.icon.Delete
import dev.wahha.wtube.ui.icon.MoreVert
import kotlinx.coroutines.launch
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.ui.downloads.PlaylistDownloadSheet
import dev.wahha.wtube.ui.icon.Download
import androidx.compose.material3.Button
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.icon.Shuffle
import dev.wahha.wtube.ui.icon.PlayArrow
import dev.wahha.wtube.player.PlayQueue
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.data.FeedCache
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.PlaylistHead
import dev.wahha.wtube.data.PlaylistDetails
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.components.BackButton
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.FreshAtTopEffect
import dev.wahha.wtube.ui.components.staleStrip
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.LikeStatus
import dev.wahha.wtube.data.innertube.PlaylistEditing
import dev.wahha.wtube.ui.components.launchAction
import dev.wahha.wtube.ui.components.movedBefore
import dev.wahha.wtube.ui.components.rememberListReorder
import dev.wahha.wtube.ui.components.VideoRow

class PlaylistViewModel(private val url: String) : ViewModel() {
    var details by mutableStateOf<PlaylistDetails?>(null)
        private set

    // Whether the playlist came as the account sees it, not from the extractor or the disk.
    private var fromAccount by mutableStateOf(false)

    // Where each loaded video stands in a playlist the account may change, by video id: edits name places, not videos.
    private val setVideoIds = HashMap<String, String>()

    // Edits go out one by one, in the order the user made them.
    private val edits = Mutex()

    // The first page comes with the playlist header; later pages are fetched by cursor.
    val list = PagedList(
        viewModelScope,
        Item::url,
        cache = { FeedCache.playlist(url) { details?.run { PlaylistHead(name, thumbnail, uploader, count) } } },
        onCached = { cached ->
            cached.playlist?.let { details = PlaylistDetails(url, it.name, it.thumbnail, it.uploader, it.count, ItemsPage(cached.items, null)) }
        },
    ) { page ->
        when {
            page == null -> load().also {
                details = it
                setVideoIds.clear()
                setVideoIds.putAll(it.setVideoIds)
            }.firstPage
            Feeds.isCursor(page) -> Feeds.more(page, setVideoIds)
            else -> YouTube.playlistMore(url, page)
        }
    }

    /**
     * Signed in, as the account sees it: private playlists, Watch later and Liked open only that way.
     * A public one falls back to the extractor if the account's request fails.
     */
    private suspend fun load(): PlaylistDetails {
        fromAccount = false
        if (Account.active == null) return YouTube.playlist(url)
        return try {
            Feeds.playlist(url).also { fromAccount = true }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (YouTube.playlistIdOf(url) in setOf(Feeds.WATCH_LATER, Feeds.LIKED)) throw e
            Log.w("Playlist", "Account request failed, extractor instead (${e.javaClass.simpleName})")
            YouTube.playlist(url)
        }
    }

    init {
        list.refresh()
    }

    private val liked get() = YouTube.playlistIdOf(url) == Feeds.LIKED

    /** The account's own playlist or Watch later, fresh from YouTube: an older copy on the screen is read only. */
    val editing: PlaylistEditing? get() = details?.editing?.takeIf { !list.stale }

    /** Videos can be taken out: from an editable playlist, and from Liked by taking the like back. */
    val canRemove get() = !list.stale && (editing != null || liked && fromAccount)

    /** Takes [video] out at once; it comes back where it was if YouTube refuses. */
    fun remove(video: VideoItem) {
        val playlist = editing
        val place = setVideoIds[video.id]
        val action: suspend () -> Unit = when {
            playlist != null && place != null -> { { Actions.removeFromPlaylist(playlist, place) } }
            liked && canRemove -> { { Actions.rate(video.id, LikeStatus.INDIFFERENT) } }
            else -> return
        }
        val at = list.items.indexOfFirst { it.url == video.url }
        list.change { items -> items.filterNot { it.url == video.url } }
        launchAction(R.string.playlist_removed, onFailure = {
            list.change { items -> if (items.any { it.url == video.url }) items else items.toMutableList().apply { add(at.coerceIn(0, size), video) } }
        }) { edits.withLock { action() } }
    }

    /** Puts [moved] right above [before] (null: below the last loaded video); it goes back if YouTube refuses. */
    fun move(moved: Item, before: Item?) {
        val playlist = editing?.takeIf { it.canReorder } ?: return
        val video = moved as? VideoItem ?: return
        val place = setVideoIds[video.id] ?: return
        val old = list.items
        val wasBefore = old.getOrNull(old.indexOfFirst { it.url == moved.url } + 1)
        val order = old.movedBefore(Item::url, moved.url, before?.url)
        // The end of what is loaded is not the end of a playlist with pages still unread: the place above is named instead.
        val above = order.getOrNull(order.indexOfFirst { it.url == moved.url } - 1) as? VideoItem
        val successor = (before as? VideoItem)?.let { setVideoIds[it.id] ?: return }
        val predecessor = if (successor == null) above?.let { setVideoIds[it.id] ?: return } else null
        if (successor == null && predecessor == null) return
        list.change { it.movedBefore(Item::url, moved.url, before?.url) }
        launchAction(onFailure = { list.change { it.movedBefore(Item::url, moved.url, wasBefore?.url) } }) {
            edits.withLock { Actions.moveInPlaylist(playlist, place, successor, predecessor) }
        }
    }

    fun rename(name: String) {
        val playlist = editing ?: return
        val was = details ?: return
        details = was.copyNamed(name)
        launchAction(R.string.playlist_renamed, onFailure = { details = details?.copyNamed(was.name) }) {
            edits.withLock { Actions.renamePlaylist(playlist, name) }
        }
    }

    /** Deletes the playlist on YouTube; [gone] runs once it is. */
    fun delete(gone: () -> Unit) {
        val playlist = editing ?: return
        val name = details?.name.orEmpty()
        viewModelScope.launch {
            try {
                edits.withLock { Actions.deletePlaylist(playlist.playlistId) }
                Notices.show(R.string.playlist_deleted, name)
                gone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notices.failed(e)
            }
        }
    }

    private fun PlaylistDetails.copyNamed(name: String) =
        PlaylistDetails(url, name, thumbnail, uploader, count, firstPage, editing, setVideoIds)

    /** Every video of the playlist, all pages of it (up to [MAX_VIDEOS]), not only the ones scrolled to. */
    private suspend fun allVideos(): List<VideoItem> {
        val first = details?.firstPage ?: return emptyList()
        val items = first.items.toMutableList()
        var next = first.next
        while (next != null && items.size < MAX_VIDEOS) {
            val page = if (Feeds.isCursor(next)) Feeds.more(next) else YouTube.playlistMore(url, next)
            items += page.items
            next = page.next
        }
        return items.filterIsInstance<VideoItem>()
    }

    /** Queues every video of the playlist for download. */
    fun download(audioOnly: Boolean, quality: Int) {
        viewModelScope.launch {
            try {
                val videos = allVideos().filter { !it.isLive && !it.membersOnly && it.duration > 0 }
                val queued = Downloads.enqueueAll(videos, audioOnly, quality)
                if (queued > 0) Notices.show(R.string.downloads_playlist_queued, queued.toString()) else Notices.show(R.string.downloads_playlist_nothing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notices.failed(e)
            }
        }
    }

    /** The whole playlist goes to the queue, the first video starts; [open] is called with it. */
    fun playAll(shuffle: Boolean, open: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val all = allVideos()
                val videos = all.filterNot { it.membersOnly }.let { if (shuffle) it.shuffled() else it }
                val first = videos.firstOrNull()
                if (first == null) {
                    if (all.isNotEmpty()) Notices.show(R.string.members_only_playlist)
                    return@launch
                }
                PlayQueue.replace(videos.drop(1))
                open(first.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Notices.failed(e)
            }
        }
    }
}

// A safety cap: Watch later can hold thousands.
private const val MAX_VIDEOS = 500

@Composable
fun PlaylistScreen(url: String) {
    val vm: PlaylistViewModel = viewModel(key = url) { PlaylistViewModel(url) }
    val list = vm.list
    val details = vm.details
    val listState = rememberLazyListState()
    val nav = LocalNavigator.current
    fun playAll(shuffle: Boolean) = vm.playAll(shuffle) { nav.openVideo(it) }
    var downloading by remember { mutableStateOf(false) }
    if (downloading) PlaylistDownloadSheet(onPick = vm::download, onDismiss = { downloading = false })
    val editing = vm.editing
    val canRemove = vm.canRemove
    val reorder = rememberListReorder(listState, list.items, Item::url, vm::move)
    val removeLabel = if (YouTube.playlistIdOf(url) == Feeds.LIKED) R.string.playlist_remove_liked else R.string.playlist_remove
    LoadMoreEffect(listState) { list.loadMore() }
    FreshAtTopEffect(list, listState)

    Box(Modifier.fillMaxSize()) {
        when {
            details == null && list.error != null -> ErrorState(list.error!!, list::retry, Modifier.align(Alignment.Center))
            details == null -> FeedSkeleton(Modifier.statusBarsPadding().padding(top = 64.dp), compact = true)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = systemTop() + 56.dp,
                    bottom = systemBottom() + LocalBottomInset.current,
                ),
            ) {
                item(key = "header") {
                    Header(
                        details,
                        canPlay = list.items.any { it is VideoItem },
                        onPlay = ::playAll,
                        onDownload = { downloading = true },
                        // Watch later has no name or existence of its own to change.
                        editing = editing?.takeIf { it.playlistId != Feeds.WATCH_LATER },
                        onRename = vm::rename,
                        onDelete = { vm.delete(nav.back) },
                    )
                }
                staleStrip(list)
                val canReorder = editing?.canReorder == true && list.items.size > 1
                items(reorder.rows, key = { it.url }) { item ->
                    if (item is VideoItem && canRemove) {
                        VideoRow(
                            item,
                            modifier = if (canReorder) reorder.row(this, item.url) else Modifier,
                            handle = if (canReorder) reorder.handle(item.url) else null,
                            dragging = reorder.isDragged(item.url),
                            removeLabel = removeLabel,
                            onRemove = { vm.remove(item) },
                        ) { nav.openVideo(item.id, it) }
                    } else {
                        ItemView(item, compact = true)
                    }
                }
                item(key = "footer") { ListFooter(list.loading, list.pageError, list::retry) }
            }
        }
        BackButton(Modifier.statusBarsPadding().padding(8.dp))
    }
}

@Composable
private fun Header(
    details: PlaylistDetails,
    canPlay: Boolean,
    onPlay: (shuffle: Boolean) -> Unit,
    onDownload: () -> Unit,
    editing: PlaylistEditing?,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Thumbnail(details.thumbnail, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
        val name = when (YouTube.playlistIdOf(details.url)) {
            Feeds.WATCH_LATER -> stringResource(R.string.library_watch_later)
            Feeds.LIKED -> stringResource(R.string.library_liked)
            else -> details.name
        }
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, style = MaterialTheme.typography.headlineMedium)
                val count = details.count.takeIf { it >= 0 }?.let { pluralStringResource(R.plurals.videos_count, it.toInt(), it.toString()) }
                Text(listOfNotNull(details.uploader, count).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            if (canPlay) {
                FilledTonalIconButton(onClick = onDownload) {
                    Icon(WIcons.Download, contentDescription = stringResource(R.string.downloads_playlist))
                }
            }
            if (editing != null) PlaylistMenu(details.name, onRename, onDelete)
        }
        if (canPlay) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPlay(false) }, modifier = Modifier.weight(1f)) {
                    Icon(WIcons.PlayArrow, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.playlist_play_all), color = LocalContentColor.current)
                }
                FilledTonalButton(onClick = { onPlay(true) }, modifier = Modifier.weight(1f)) {
                    Icon(WIcons.Shuffle, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.playlist_shuffle), color = LocalContentColor.current)
                }
            }
        }
    }
}

/** "⋮" of the account's own playlist: rename it, delete it (after asking). */
@Composable
private fun PlaylistMenu(name: String, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    if (renaming) {
        var text by remember { mutableStateOf(name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.playlist_rename)) },
            text = {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.playlist_name)) }, singleLine = true)
            },
            confirmButton = {
                TextButton(
                    enabled = text.isNotBlank() && text.trim() != name,
                    onClick = {
                        renaming = false
                        onRename(text.trim())
                    },
                ) { Text(stringResource(R.string.playlist_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            text = { Text(stringResource(R.string.playlist_delete_confirm, name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    onDelete()
                }) { Text(stringResource(R.string.playlist_delete_action)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(WIcons.MoreVert, contentDescription = stringResource(R.string.item_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_rename)) },
                onClick = {
                    open = false
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete)) },
                leadingIcon = { Icon(WIcons.Delete, contentDescription = null) },
                onClick = {
                    open = false
                    deleting = true
                },
            )
        }
    }
}
