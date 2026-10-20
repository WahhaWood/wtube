package dev.wahha.wtube.ui.playlist

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
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
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.PlaylistDetails
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.components.BackButton
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.Thumbnail
import kotlinx.coroutines.CancellationException

class PlaylistViewModel(private val url: String) : ViewModel() {
    var details by mutableStateOf<PlaylistDetails?>(null)
        private set

    // The first page comes with the playlist header; later pages are fetched by cursor.
    val list = PagedList(viewModelScope, Item::url) { page ->
        when {
            page == null -> load().also { details = it }.firstPage
            Feeds.isCursor(page) -> Feeds.more(page)
            else -> YouTube.playlistMore(url, page)
        }
    }

    /**
     * Signed in, as the account sees it: private playlists, Watch later and Liked open only that way.
     * A public one falls back to the extractor if the account's request fails.
     */
    private suspend fun load(): PlaylistDetails {
        if (Account.active == null) return YouTube.playlist(url)
        return try {
            Feeds.playlist(url)
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
}

@Composable
fun PlaylistScreen(url: String) {
    val vm: PlaylistViewModel = viewModel(key = url) { PlaylistViewModel(url) }
    val list = vm.list
    val details = vm.details
    val listState = rememberLazyListState()
    val nav = LocalNavigator.current
    // What is loaded so far goes to the queue; the first video starts now.
    fun playAll(shuffle: Boolean) {
        val videos = list.items.filterIsInstance<VideoItem>().let { if (shuffle) it.shuffled() else it }
        val first = videos.firstOrNull() ?: return
        PlayQueue.replace(videos.drop(1))
        nav.openVideo(first.id)
    }
    LoadMoreEffect(listState) { list.loadMore() }

    Box(Modifier.fillMaxSize()) {
        when {
            details == null && list.error != null -> ErrorState(list.error!!, list::retry, Modifier.align(Alignment.Center))
            details == null -> FeedSkeleton(Modifier.statusBarsPadding().padding(top = 64.dp), compact = true)
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 56.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalBottomInset.current,
                ),
            ) {
                item(key = "header") { Header(details, canPlay = list.items.any { it is VideoItem }, onPlay = ::playAll) }
                items(list.items, key = { it.url }) { ItemView(it, compact = true) }
                item(key = "footer") { ListFooter(list.loading, list.error, list::retry) }
            }
        }
        BackButton(Modifier.statusBarsPadding().padding(8.dp))
    }
}

@Composable
private fun Header(details: PlaylistDetails, canPlay: Boolean, onPlay: (shuffle: Boolean) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Thumbnail(details.thumbnail, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)))
        val name = when (YouTube.playlistIdOf(details.url)) {
            Feeds.WATCH_LATER -> stringResource(R.string.library_watch_later)
            Feeds.LIKED -> stringResource(R.string.library_liked)
            else -> details.name
        }
        Text(name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        val count = details.count.takeIf { it >= 0 }?.let { pluralStringResource(R.plurals.videos_count, it.toInt(), it.toString()) }
        Text(listOfNotNull(details.uploader, count).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
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
