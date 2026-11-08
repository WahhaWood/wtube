package dev.wahha.wtube.ui.components

import dev.wahha.wtube.data.ErrorLog
import android.util.Log
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import dev.wahha.wtube.data.Cached
import dev.wahha.wtube.data.ListSlot
import dev.wahha.wtube.data.MAX_CACHED_ITEMS
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.account.SessionExpiredException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import kotlin.coroutines.cancellation.CancellationException

/**
 * An endless list fed page by page; lives in a ViewModel, read from Compose. [key] dedupes across pages.
 * With [cache] it opens with what was loaded last time, before any network wait, and a failed refresh keeps
 * what is on the screen. The answer of [cache] can change with the account, so it is asked on every refresh.
 */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
    private val cache: (() -> ListSlot<T>?)? = null,
    private val onCached: (Cached<T>) -> Unit = {},
    private val load: suspend (Page?) -> PageOf<T>,
) {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var endReached by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** The items are an older copy: read from the disk, or kept when a refresh failed. They have no next page. */
    var stale by mutableStateOf(false)
        private set

    /** When the items were loaded; null before anything was. */
    var loadedAt by mutableStateOf<Long?>(null)
        private set

    private var next: Page? = null
    private var job: Job? = null
    private var slot: ListSlot<T>? = null

    // The fresh first page, waiting while the user reads further down the older copy.
    private var held: PageOf<T>? = null
    private var nearTop = true
    private var replaceNow = false

    /** True before the first page arrived: show a skeleton, not an empty list. */
    val initialLoading get() = items.isEmpty() && loading

    /** A failure to show at the end of the list; a stale list tells about its own at the top ([staleStrip]). */
    val pageError get() = error.takeUnless { stale }

    fun refresh(pullToRefresh: Boolean = false) = restart(pullToRefresh, replaceNow = pullToRefresh)

    fun loadMore() {
        if (loading || endReached || error != null) return
        val fresh = held
        when {
            // At the end of the older copy: what is new goes below it, nothing moves under the reader.
            fresh != null -> showFresh(fresh, (items + fresh.items).distinctBy(key))
            !stale -> launchLoad(reset = false)
        }
    }

    fun retry() {
        error = null
        if (items.isEmpty() || stale) restart(pullToRefresh = false, replaceNow = true) else loadMore()
    }

    /** Changes the items in place, for an edit the user made to the list itself; the copy on disk follows. */
    fun change(transform: (List<T>) -> List<T>) {
        items = transform(items)
        held = held?.let { PageOf(transform(it.items), it.next) }
        slot?.write(items)
    }

    /** Whether the user is near the top of the list; there a waiting fresh page can replace it unnoticed. */
    fun onNearTop(near: Boolean) {
        nearTop = near
        if (near) held?.let { showFresh(it, it.items) }
    }

    private fun restart(pullToRefresh: Boolean, replaceNow: Boolean) {
        job?.cancel()
        next = null
        endReached = false
        held = null
        refreshing = pullToRefresh
        this.replaceNow = replaceNow
        launchLoad(reset = true)
    }

    private fun showFresh(page: PageOf<T>, shown: List<T>) {
        items = shown
        next = page.next
        endReached = next == null
        stale = false
        loadedAt = System.currentTimeMillis()
        held = null
    }

    private fun launchLoad(reset: Boolean) {
        loading = true
        error = null
        if (reset) openSlot()
        val slot = slot
        val job = scope.launch {
            try {
                if (reset && items.isEmpty()) slot?.read()?.takeIf { it.items.isNotEmpty() }?.let { cached ->
                    items = cached.items
                    stale = true
                    loadedAt = cached.savedAt
                    onCached(cached)
                }
                var page = load(next)
                val base = if (reset) emptyList() else items
                var merged = (base + page.items).distinctBy(key)
                // Filtering shorts can empty a page, and a page can hold only items we already have;
                // keep going a little so the list does not stall.
                var extra = 0
                var asked = next
                while (merged.size == base.size && page.next != null && !page.next.sameAs(asked) && extra++ < 3) {
                    asked = page.next
                    page = load(page.next)
                    merged = (merged + page.items).distinctBy(key)
                }
                val before = items.size
                if (reset || before < MAX_CACHED_ITEMS) slot?.write(merged)
                if (reset && stale && !replaceNow && !nearTop) {
                    held = PageOf(merged, page.next)
                    return@launch
                }
                items = merged
                // The same cursor again with nothing new would loop forever: take it as the end.
                val stuck = !reset && items.size == before && page.next.sameAs(next)
                next = page.next.takeUnless { stuck }
                endReached = next == null
                stale = false
                loadedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PagedList", "Page failed: ${errorDetail(e)}")
                ErrorLog.note("[feed] page failed: ${errorDetail(e)}")
                if (reset) {
                    // Only a list that has a place on disk stays, and never as the feed of an account that is gone.
                    if (slot == null || e is SessionExpiredException) items = emptyList()
                    stale = items.isNotEmpty()
                }
                error = e
            }
        }
        this.job = job
        job.invokeOnCompletion {
            // A cancelled refresh must not clear the flags of the load that replaced it.
            if (this.job === job) {
                loading = false
                refreshing = false
            }
        }
    }

    /** Another slot means another account's list: what is on the screen must not stay under it. */
    private fun openSlot() {
        val opened = cache?.invoke()
        if (opened?.id != slot?.id) {
            items = emptyList()
            stale = false
            loadedAt = null
        }
        slot = opened
    }
}

/** The row that tells a [PagedList] on the screen is an older copy, once refreshing it has failed. */
fun LazyListScope.staleStrip(list: PagedList<*>) {
    val error = list.error
    if (list.stale && error != null) item(key = "stale") { StaleStrip(error, list.loadedAt, list::retry) }
}

/** Keeps [list] told whether [state] is near its top: further down, a fresh page waits instead of replacing the list. */
@Composable
fun FreshAtTopEffect(list: PagedList<*>, state: LazyListState) {
    LaunchedEffect(list, state) {
        snapshotFlow { state.firstVisibleItemIndex < NEAR_TOP_ROWS }.collect(list::onNearTop)
    }
}

// Rows above the feed included: the header, the expired banner, the channel strip.
private const val NEAR_TOP_ROWS = 4

/** Calls [onLoadMore] when the last visible row is within [threshold] of the end. */
@Composable
fun LoadMoreEffect(
    listState: LazyListState,
    threshold: Int = 6,
    // Items loaded so far, for a screen that filters them: a page it hides whole leaves the list the same size.
    loaded: Int = 0,
    onLoadMore: () -> Unit,
) {
    val nearEnd by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - threshold
        }
    }
    val total by remember(listState) { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    // The list behind the same state can change (a channel tab or sort): always page the current one.
    val loadMore by rememberUpdatedState(onLoadMore)
    val loadedNow by rememberUpdatedState(loaded)
    LaunchedEffect(listState) {
        // Keyed on the size too: a page that leaves the list still near its end must load the next one.
        snapshotFlow { Triple(nearEnd, total, loadedNow) }.filter { it.first }.collect { loadMore() }
    }
}

private fun Page?.sameAs(other: Page?) = this != null && id == other?.id && url == other?.url
