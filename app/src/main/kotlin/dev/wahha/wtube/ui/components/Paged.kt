package dev.wahha.wtube.ui.components

import android.util.Log
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                val fresh = page.items.toMutableList()
                // Filtering shorts can empty a page; keep going a little so the list does not stall.
                var extra = 0
                while (fresh.isEmpty() && page.next != null && extra++ < 3) {
                    page = load(page.next)
                    fresh += page.items
                }
                items = ((if (reset) emptyList() else items) + fresh).distinctBy(key)
                next = page.next
                endReached = page.next == null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PagedList", "Page failed: ${errorDetail(e)}")
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
    LaunchedEffect(listState) {
        snapshotFlow { nearEnd }.filter { it }.collect { onLoadMore() }
    }
}
