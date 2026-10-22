package dev.wahha.wtube.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.account.ExpiredBanner
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.History
import dev.wahha.wtube.ui.icon.WIcons

class FeedViewModel(browseId: String) : ViewModel() {
    val list = PagedList(viewModelScope, Item::url) { page ->
        if (browseId == Feeds.PLAYLISTS) Feeds.playlists(page) else Feeds.feed(browseId, page)
    }

    init {
        list.refresh()
    }
}

/** One of the account's lists that has no screen of its own: YouTube history, all channels, playlists. */
@Composable
fun FeedScreen(browseId: String) {
    val vm: FeedViewModel = viewModel(key = browseId) { FeedViewModel(browseId) }
    val list = vm.list
    val nav = LocalNavigator.current
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { list.loadMore() }
    val title = when (browseId) {
        Feeds.HISTORY -> R.string.library_youtube_history
        Feeds.CHANNELS -> R.string.subscriptions_channels_all
        Feeds.PLAYLISTS -> R.string.library_playlists
        else -> R.string.app_name
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = systemTop(),
            bottom = systemBottom() + LocalBottomInset.current,
        ),
    ) {
        item(key = "header") {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item(key = "expired") { ExpiredBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
        when {
            list.initialLoading -> item(key = "skeleton") { FeedSkeleton(compact = true) }
            list.items.isEmpty() && list.error != null -> item(key = "error") { ErrorState(list.error!!, list::retry) }
            list.items.isEmpty() && list.endReached -> item(key = "empty") { MessageState(WIcons.History, stringResource(R.string.feed_empty)) }
            else -> {
                items(list.items, key = { it.url }) { ItemView(it, compact = true) }
                item(key = "footer") { ListFooter(list.loading, list.error, list::retry) }
            }
        }
    }
}
