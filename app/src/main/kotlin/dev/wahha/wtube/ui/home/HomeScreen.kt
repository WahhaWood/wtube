package dev.wahha.wtube.ui.home

import dev.wahha.wtube.data.ErrorLog
import android.util.Log
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.errorDetail
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.account.ExpiredBanner
import kotlinx.coroutines.CancellationException
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LogoMark
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.ScreenHeader
import dev.wahha.wtube.ui.icon.Home
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.components.TabResetEffect
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop

private const val MAX_SEEDS = 30

/**
 * YouTube's own home when signed in; otherwise, or when that comes back empty (history paused) or
 * fails, recommendations built from local watch history. The seeds are fixed when the feed loads;
 * newer watches come in on pull-to-refresh or the next launch, so coming back from a video keeps the
 * scroll position.
 */
class HomeViewModel : ViewModel() {
    private var seeds = emptyList<String>()
    private var watched = emptySet<String>()
    private var signedIn = false
    private var loadedFor: Any? = Unloaded

    /** Null until history and the account are read; then whether there is anything to show. */
    var hasSource by mutableStateOf<Boolean?>(null)
        private set

    val feed = PagedList(viewModelScope, Item::url) { page ->
        when {
            page != null && Feeds.isCursor(page) -> Feeds.feed(Feeds.HOME, page)
            page != null -> YouTube.recommendations(seeds, watched, page)
            signedIn -> accountHome()
            else -> YouTube.recommendations(seeds, watched)
        }
    }

    private suspend fun accountHome(): ItemsPage {
        val home = try {
            Feeds.feed(Feeds.HOME)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (seeds.isEmpty()) throw e
            Log.w("Home", "Account home failed, local recommendations instead: ${errorDetail(e)}")
            ErrorLog.note("[home] account home failed, local recommendations instead: ${errorDetail(e)}")
            Notices.show(R.string.home_account_failed, errorDetail(e))
            null
        }
        if (home != null && home.items.isEmpty()) {
            Log.w("Home", "Account home is empty")
            if (seeds.isNotEmpty()) Notices.show(R.string.home_account_empty)
        }
        return home?.takeIf { it.items.isNotEmpty() || seeds.isEmpty() } ?: YouTube.recommendations(seeds, watched)
    }

    /**
     * Loads the feed once history and the account are known, and again when the account changes.
     * Signed out, the very first watched video starts the feed too.
     */
    fun sync(session: Session?, historyEmpty: Boolean) {
        val key = session?.let { listOf(it.cookie, it.authUser, it.pageId, it.expired) } ?: historyEmpty
        if (key == loadedFor) return
        loadedFor = key
        reload()
    }

    fun reload(pullToRefresh: Boolean = false) {
        val entries = History.entries.value
        seeds = entries.take(MAX_SEEDS).map { it.video.id }
        watched = entries.map { it.video.id }.toSet()
        signedIn = Account.active != null
        hasSource = signedIn || seeds.isNotEmpty()
        if (hasSource == true) feed.refresh(pullToRefresh)
    }

    private object Unloaded
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val feed = vm.feed
    val nav = LocalNavigator.current
    val historyLoaded by History.loaded.collectAsStateWithLifecycle()
    val entries by History.entries.collectAsStateWithLifecycle()
    val accountLoaded by Account.loaded.collectAsStateWithLifecycle()
    val session by Account.session.collectAsStateWithLifecycle()
    val hidden by Hidden.items.collectAsStateWithLifecycle()
    LaunchedEffect(historyLoaded, accountLoaded, session, entries.isEmpty()) {
        if (historyLoaded && accountLoaded) vm.sync(session, entries.isEmpty())
    }
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { feed.loadMore() }
    TabResetEffect { listState.animateScrollToItem(0) }

    PullToRefreshBox(
        isRefreshing = feed.refreshing,
        onRefresh = { vm.reload(pullToRefresh = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("home_feed"),
            contentPadding = PaddingValues(
                top = systemTop(),
                bottom = systemBottom() + LocalBottomInset.current,
            ),
        ) {
            item(key = "header") { ScreenHeader(stringResource(R.string.app_name), leading = { LogoMark() }) }
            item(key = "expired") { ExpiredBanner(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            when {
                vm.hasSource == false || feed.items.isEmpty() && feed.endReached && feed.error == null -> item(key = "empty") {
                    MessageState(
                        WIcons.Home,
                        stringResource(R.string.home_empty_body),
                        title = stringResource(R.string.home_empty_title),
                    ) {
                        FilledTonalButton(onClick = nav.openSearch) { Text(stringResource(R.string.home_find)) }
                    }
                }
                vm.hasSource == null || feed.initialLoading -> item(key = "skeleton") { FeedSkeleton() }
                feed.items.isEmpty() && feed.error != null -> item(key = "error") { ErrorState(feed.error!!, feed::retry) }
                else -> {
                    items(feed.items.filter(hidden::allows), key = { it.url }) { ItemView(it, recommended = true) }
                    item(key = "footer") { ListFooter(feed.loading, feed.error, feed::retry) }
                }
            }
        }
    }
}
