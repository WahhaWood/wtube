package dev.wahha.wtube.ui.subscriptions

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.account.ExpiredBanner
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.ScreenHeader
import dev.wahha.wtube.ui.icon.AccountCircle
import dev.wahha.wtube.ui.icon.ChevronRight
import dev.wahha.wtube.ui.icon.Subscriptions
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** New videos from subscribed channels, with the channels themselves in a row above. */
class SubscriptionsViewModel : ViewModel() {
    val videos = PagedList(viewModelScope, Item::url) { page -> Feeds.feed(Feeds.SUBSCRIPTIONS, page) }

    var channels by mutableStateOf<List<ChannelItem>>(emptyList())
        private set

    private var loadedFor: Any? = null

    fun sync(session: Session?) {
        val key = session?.takeUnless { it.expired }?.let { listOf(it.cookie, it.authUser, it.pageId) }
        if (key == loadedFor) return
        loadedFor = key
        if (key != null) reload()
    }

    fun reload(pullToRefresh: Boolean = false) {
        videos.refresh(pullToRefresh)
        viewModelScope.launch {
            // The row is extra: without it the feed still works.
            channels = try {
                Feeds.feed(Feeds.CHANNELS).items.filterIsInstance<ChannelItem>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Subscriptions", "Channel list failed (${e.javaClass.simpleName})")
                emptyList()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(vm: SubscriptionsViewModel = viewModel()) {
    val nav = LocalNavigator.current
    val session by Account.session.collectAsStateWithLifecycle()
    val loaded by Account.loaded.collectAsStateWithLifecycle()
    LaunchedEffect(session) { vm.sync(session) }
    val videos = vm.videos
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { videos.loadMore() }
    val active = session?.expired == false
    val settings by Settings.current.collectAsStateWithLifecycle()
    val history by History.entries.collectAsStateWithLifecycle()
    val finished = remember(history, settings.hideWatched) {
        if (settings.hideWatched) history.filter { it.finished }.mapTo(HashSet()) { it.video.id } else emptySet()
    }

    PullToRefreshBox(
        isRefreshing = videos.refreshing,
        onRefresh = { if (active) vm.reload(pullToRefresh = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("subscriptions_feed"),
            contentPadding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalBottomInset.current,
            ),
        ) {
            item(key = "header") { ScreenHeader(stringResource(R.string.tab_subscriptions)) }
            item(key = "expired") { ExpiredBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            when {
                !loaded -> Unit
                session == null -> item(key = "signed_out") {
                    MessageState(
                        WIcons.AccountCircle,
                        stringResource(R.string.subscriptions_body),
                        title = stringResource(R.string.subscriptions_title),
                    ) {
                        FilledTonalButton(onClick = nav.openLogin) { Text(stringResource(R.string.account_sign_in)) }
                    }
                }
                !active -> Unit
                else -> {
                    if (vm.channels.isNotEmpty()) item(key = "channels") { ChannelStrip(vm.channels) { nav.openFeed(Feeds.CHANNELS) } }
                    when {
                        videos.initialLoading -> item(key = "skeleton") { FeedSkeleton() }
                        videos.items.isEmpty() && videos.error != null -> item(key = "error") { ErrorState(videos.error!!, videos::retry) }
                        videos.items.isEmpty() && videos.endReached -> item(key = "empty") {
                            MessageState(WIcons.Subscriptions, stringResource(R.string.subscriptions_empty))
                        }
                        else -> {
                            items(videos.items.filter { it !is VideoItem || it.id !in finished }, key = { it.url }) { ItemView(it) }
                            item(key = "footer") { ListFooter(videos.loading, videos.error, videos::retry) }
                        }
                    }
                }
            }
        }
    }
}

/** Subscribed channels as avatars in a row, ending with the way to the full list. */
@Composable
private fun ChannelStrip(channels: List<ChannelItem>, onAll: () -> Unit) {
    val nav = LocalNavigator.current
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(channels, key = { it.url }) { channel ->
            StripEntry(channel.name, onClick = { nav.openChannel(channel.url) }) { Avatar(channel.avatar, 56.dp) }
        }
        item(key = "all") {
            StripEntry(stringResource(R.string.subscriptions_channels_all), onClick = onAll) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(WIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun StripEntry(label: String, onClick: () -> Unit, image: @Composable () -> Unit) {
    Column(
        Modifier.width(72.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        image()
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}
