package dev.wahha.wtube.ui.library

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.HistoryEntry
import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.ui.account.ExpiredBanner
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.ScreenHeader
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.ThumbnailAnchor
import dev.wahha.wtube.ui.components.VideoOrigin
import dev.wahha.wtube.ui.components.TabResetEffect
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.icon.ChevronRight
import dev.wahha.wtube.ui.icon.Download
import dev.wahha.wtube.ui.icon.BarChart
import dev.wahha.wtube.ui.icon.History
import dev.wahha.wtube.ui.icon.ThumbUp
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.icon.WatchLater
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val CardWidth = 160.dp
private val CardCorner = 12.dp
private val CardShape = RoundedCornerShape(CardCorner)

/** The account's playlists for the Library row; loaded once per account, when the tab is opened. */
class LibraryViewModel(
    private val loadPlaylists: suspend () -> List<PlaylistItem> = { Feeds.playlists().items.filterIsInstance<PlaylistItem>() },
) : ViewModel() {
    var playlists by mutableStateOf<List<PlaylistItem>>(emptyList())
        private set
    private var loadedFor: Any? = null
    private var job: Job? = null

    fun sync(session: Session?) {
        val key = session?.takeUnless { it.expired }?.let { listOf(it.cookie, it.authUser, it.pageId) }
        if (key == loadedFor) return
        loadedFor = key
        job?.cancel()
        playlists = emptyList()
        if (key == null) return
        job = viewModelScope.launch {
            val loaded = runCatching { loadPlaylists() }.getOrDefault(emptyList())
            // Switched or signed out while it loaded: the answer belongs to the previous account.
            if (loadedFor == key) playlists = loaded
        }
    }
}

/**
 * Like YouTube's "You" tab: recent history as a row of cards, the account's playlists as another (Watch
 * later and Liked first), then downloads and the account's YouTube history. The full history has its own page.
 */
@Composable
fun LibraryScreen(vm: LibraryViewModel = viewModel()) {
    val entries by History.entries.collectAsStateWithLifecycle()
    val downloads by Downloads.entries.collectAsStateWithLifecycle()
    val session by Account.session.collectAsStateWithLifecycle()
    val nav = LocalNavigator.current
    LaunchedEffect(session) { vm.sync(session) }
    val signedIn = session?.expired == false
    val listState = rememberLazyListState()
    TabResetEffect { listState.animateScrollToItem(0) }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = systemTop(),
            bottom = systemBottom() + LocalBottomInset.current,
        ),
    ) {
        item { ScreenHeader(stringResource(R.string.tab_library)) }
        if (session != null) item { ExpiredBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }

        item { SectionTitle(stringResource(R.string.library_history), onAll = nav.openHistory.takeIf { entries.isNotEmpty() }) }
        item {
            if (entries.isEmpty()) {
                Text(
                    stringResource(R.string.library_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(entries.take(HISTORY_CARDS), key = { it.video.id }) {
                        Box(Modifier.animateItem()) { HistoryCard(it) { from -> nav.openVideo(it.video.id, from) } }
                    }
                }
            }
        }

        if (signedIn) {
            item { SectionTitle(stringResource(R.string.library_playlists), onAll = { nav.openFeed(Feeds.PLAYLISTS) }) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item(key = "wl") {
                        IconCard(WIcons.WatchLater, stringResource(R.string.library_watch_later)) { nav.openPlaylist(Feeds.playlistUrl(Feeds.WATCH_LATER)) }
                    }
                    item(key = "ll") {
                        IconCard(WIcons.ThumbUp, stringResource(R.string.library_liked)) { nav.openPlaylist(Feeds.playlistUrl(Feeds.LIKED)) }
                    }
                    items(vm.playlists, key = { it.url }) { playlist ->
                        Box(Modifier.animateItem()) { PlaylistCard(playlist) { nav.openPlaylist(playlist.url) } }
                    }
                }
            }
        }

        item { Box(Modifier.height(12.dp)) }
        item {
            val done = downloads.count { it.state == DownloadState.DONE }
            LinkRow(WIcons.Download, stringResource(R.string.downloads_title), done.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.videos_count, it, it.toString()) }, nav.openDownloads)
        }
        item { LinkRow(WIcons.BarChart, stringResource(R.string.stats_title), null, nav.openStats) }
        if (signedIn) {
            item { LinkRow(WIcons.History, stringResource(R.string.library_youtube_history), null) { nav.openFeed(Feeds.HISTORY) } }
        }
    }
}

@Composable
private fun SectionTitle(text: String, onAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (onAll != null) TextButton(onClick = onAll) { Text(stringResource(R.string.library_all)) }
    }
}

@Composable
private fun HistoryCard(entry: HistoryEntry, onClick: (VideoOrigin?) -> Unit) {
    val duration = entry.video.duration * 1000
    val progress = if (duration > 0) (entry.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val anchor = remember { ThumbnailAnchor(CardCorner) }
    Column(
        Modifier.width(CardWidth).clip(CardShape).clickable { onClick(anchor.origin(entry.video.thumbnail)) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumbnail(entry.video.thumbnail, Modifier.fillMaxWidth().clip(CardShape).then(anchor.modifier)) {
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
        Text(entry.video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        entry.video.channelName?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun PlaylistCard(playlist: PlaylistItem, onClick: () -> Unit) {
    Column(Modifier.width(CardWidth).clip(CardShape).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Thumbnail(playlist.thumbnail, Modifier.fillMaxWidth().clip(CardShape))
        Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (playlist.count >= 0) {
            Text(pluralStringResource(R.plurals.videos_count, playlist.count.toInt(), playlist.count.toString()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A list with no cover of its own (Watch later, Liked): its icon on a tile the size of a cover. */
@Composable
private fun IconCard(icon: ImageVector, title: String, onClick: () -> Unit) {
    Column(Modifier.width(CardWidth).clip(CardShape).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LinkRow(icon: ImageVector, text: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Icon(WIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
    }
}

private const val HISTORY_CARDS = 20
