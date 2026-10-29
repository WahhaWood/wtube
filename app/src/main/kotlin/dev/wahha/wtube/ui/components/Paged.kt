package dev.wahha.wtube.ui.components

import dev.wahha.wtube.data.ErrorLog
import android.util.Log
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
import dev.wahha.wtube.data.PageOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page
import kotlin.coroutines.cancellation.CancellationException

/** An endless list fed page by page; lives in a ViewModel, read from Compose. [key] dedupes across pages. */
class PagedList<T>(
    private val scope: CoroutineScope,
    private val key: (T) -> Any,
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

    private var next: Page? = null
    private var job: Job? = null

    /** True before the first page arrived: show a skeleton, not an empty list. */
    val initialLoading get() = items.isEmpty() && loading

    fun refresh(pullToRefresh: Boolean = false) {
        job?.cancel()
        next = null
        endReached = false
        refreshing = pullToRefresh
        launchLoad(reset = true)
    }

    fun loadMore() {
        if (loading || endReached || error != null) return
        launchLoad(reset = false)
    }

    fun retry() {
        error = null
        if (items.isEmpty()) refresh() else loadMore()
    }

    private fun launchLoad(reset: Boolean) {
        loading = true
        error = null
        val job = scope.launch {
            try {
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
                items = merged
                // The same cursor again with nothing new would loop forever: take it as the end.
                val stuck = !reset && items.size == before && page.next.sameAs(next)
                next = page.next.takeUnless { stuck }
                endReached = next == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PagedList", "Page failed: ${errorDetail(e)}")
                ErrorLog.note("[feed] page failed: ${errorDetail(e)}")
                if (reset) items = emptyList()
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
}

/** Calls [onLoadMore] when the last visible row is within [threshold] of the end. */
@Composable
fun LoadMoreEffect(listState: LazyListState, threshold: Int = 6, onLoadMore: () -> Unit) {
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
    LaunchedEffect(listState) {
        // Keyed on the size too: a page that leaves the list still near its end must load the next one.
        snapshotFlow { nearEnd to total }.filter { it.first }.collect { loadMore() }
    }
}

private fun Page?.sameAs(other: Page?) = this != null && id == other?.id && url == other?.url
