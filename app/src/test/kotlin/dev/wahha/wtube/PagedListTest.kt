package dev.wahha.wtube

import dev.wahha.wtube.data.Cached
import dev.wahha.wtube.data.ListSlot
import dev.wahha.wtube.data.MAX_CACHED_ITEMS
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.account.SessionExpiredException
import dev.wahha.wtube.ui.components.PagedList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Page

class PagedListTest {
    private fun cursor(id: String) = Page("wtube:test", id)

    private fun list(load: suspend (Page?) -> PageOf<String>) =
        PagedList(CoroutineScope(Dispatchers.Unconfined + Job()), { it }, load = load)

    @Test fun pagesAppendUntilTheLastOne() {
        val pages = mapOf(null to PageOf(listOf("a", "b"), cursor("2")), "2" to PageOf(listOf("c"), null))
        val list = list { pages.getValue(it?.id) }
        list.refresh()
        assertEquals(listOf("a", "b"), list.items)
        assertFalse(list.endReached)
        list.loadMore()
        assertEquals(listOf("a", "b", "c"), list.items)
        assertTrue(list.endReached)
    }

    @Test fun pageOfOnlyDuplicatesLoadsTheNextOne() {
        val pages = mapOf(
            null to PageOf(listOf("a", "b"), cursor("2")),
            "2" to PageOf(listOf("a", "b"), cursor("3")),
            "3" to PageOf(listOf("c"), null),
        )
        val list = list { pages.getValue(it?.id) }
        list.refresh()
        list.loadMore()
        assertEquals(listOf("a", "b", "c"), list.items)
        assertTrue(list.endReached)
    }

    @Test fun emptyLastPageEndsTheList() {
        val list = list { if (it == null) PageOf(listOf("a"), cursor("2")) else PageOf(emptyList(), null) }
        list.refresh()
        list.loadMore()
        assertEquals(listOf("a"), list.items)
        assertTrue(list.endReached)
        assertEquals(null, list.error)
    }

    @Test fun repeatedCursorWithNothingNewEndsTheList() {
        var calls = 0
        val list = list { calls++; PageOf(listOf("a"), cursor("same")) }
        list.refresh()
        list.loadMore() // same cursor "same" is now stored, the answer repeats it
        list.loadMore()
        assertEquals(listOf("a"), list.items)
        assertTrue(list.endReached)
        assertTrue("kept asking: $calls", calls <= 3)
    }

    @Test fun emptyPagesAreSkippedButNotForever() {
        var calls = 0
        val list = list { calls++; PageOf(emptyList(), cursor("n$calls")) }
        list.refresh()
        assertTrue(list.items.isEmpty())
        assertTrue("calls=$calls", calls <= 4)
    }

    @Test fun failureKeepsItemsAndAllowsRetry() {
        var fail = true
        val list = list {
            if (it == null) PageOf(listOf("a"), cursor("2")) else if (fail) throw java.io.IOException("offline") else PageOf(listOf("b"), null)
        }
        list.refresh()
        list.loadMore()
        assertNotNull(list.error)
        assertEquals(listOf("a"), list.items)
        fail = false
        list.retry()
        assertEquals(listOf("a", "b"), list.items)
        assertEquals(null, list.error)
    }

    private class Slot(override val id: String = "slot", var stored: Cached<String>? = null) : ListSlot<String> {
        val written = mutableListOf<List<String>>()
        override suspend fun read() = stored
        override fun write(items: List<String>) { written += items }
    }

    private val offline = java.io.IOException("offline")
    private fun cached(vararg items: String) = Cached(items.toList(), savedAt = 1234)

    private fun cachedList(slot: Slot, onCached: (Cached<String>) -> Unit = {}, load: suspend (Page?) -> PageOf<String>) =
        PagedList(CoroutineScope(Dispatchers.Unconfined + Job()), { it }, cache = { slot }, onCached = onCached, load = load)

    @Test fun cacheShowsBeforeTheNetworkAnswersAndIsReplaced() {
        val answer = CompletableDeferred<PageOf<String>>()
        val slot = Slot(stored = cached("old"))
        var told: Cached<String>? = null
        val list = cachedList(slot, { told = it }) { answer.await() }
        list.refresh()
        assertEquals(listOf("old"), list.items)
        assertTrue(list.stale)
        assertEquals(1234L, list.loadedAt)
        assertEquals(slot.stored, told)
        assertFalse(list.initialLoading)

        answer.complete(PageOf(listOf("new"), cursor("2")))
        assertEquals(listOf("new"), list.items)
        assertFalse(list.stale)
        assertTrue(list.loadedAt!! > 1234)
        assertEquals(listOf(listOf("new")), slot.written)
    }

    @Test fun failureAfterTheCacheKeepsTheList() {
        val slot = Slot(stored = cached("old"))
        val list = cachedList(slot) { throw offline }
        list.refresh()
        assertEquals(listOf("old"), list.items)
        assertTrue(list.stale)
        assertEquals(offline, list.error)
        assertNull("the strip at the top tells it, not the footer", list.pageError)
        assertEquals(1234L, list.loadedAt)
        assertTrue(slot.written.isEmpty())
    }

    @Test fun nothingCachedAndFailureIsAnError() {
        val list = cachedList(Slot()) { throw offline }
        list.refresh()
        assertTrue(list.items.isEmpty())
        assertFalse(list.stale)
        assertEquals(offline, list.error)
    }

    @Test fun anEmptyCacheIsNotAList() {
        val list = cachedList(Slot(stored = cached())) { throw offline }
        list.refresh()
        assertFalse(list.stale)
        assertNull(list.loadedAt)
    }

    @Test fun anExpiredSessionLeavesNothingOnScreen() {
        val list = cachedList(Slot(stored = cached("old"))) { throw SessionExpiredException() }
        list.refresh()
        assertTrue(list.items.isEmpty())
        assertFalse(list.stale)
    }

    @Test fun aListWithoutACacheStillEmptiesOnFailure() {
        var fail = false
        val list = list { if (fail) throw offline else PageOf(listOf("a"), null) }
        list.refresh()
        fail = true
        list.refresh()
        assertTrue(list.items.isEmpty())
        assertFalse(list.stale)
    }

    @Test fun failedPullKeepsWhatIsOnScreenAndRetryRefreshes() {
        var fail = false
        var calls = 0
        val list = cachedList(Slot()) { calls++; if (fail) throw offline else PageOf(listOf("a"), cursor("2")) }
        list.refresh()
        fail = true
        list.refresh(pullToRefresh = true)
        assertEquals(listOf("a"), list.items)
        assertTrue(list.stale)
        assertFalse(list.refreshing)

        fail = false
        calls = 0
        list.retry()
        assertEquals(1, calls)
        assertFalse(list.stale)
        assertNull(list.error)
    }

    @Test fun aStaleListAsksForNoNextPage() {
        var calls = 0
        val list = cachedList(Slot(stored = cached("old"))) { calls++; throw offline }
        list.refresh()
        list.loadMore()
        assertEquals(1, calls)
    }

    @Test fun farDownTheFreshListWaitsForTheTop() {
        val answer = CompletableDeferred<PageOf<String>>()
        val slot = Slot(stored = cached("old1", "old2"))
        val list = cachedList(slot) { answer.await() }
        list.refresh()
        list.onNearTop(false)
        answer.complete(PageOf(listOf("new"), cursor("2")))
        assertEquals(listOf("old1", "old2"), list.items)
        assertTrue(list.stale)
        assertEquals("saved at once, shown later", listOf(listOf("new")), slot.written)

        list.onNearTop(true)
        assertEquals(listOf("new"), list.items)
        assertFalse(list.stale)
        assertFalse(list.endReached)
    }

    @Test fun atTheEndOfTheOldCopyTheFreshRowsGoBelow() {
        val answer = CompletableDeferred<PageOf<String>>()
        val list = cachedList(Slot(stored = cached("old", "both"))) { answer.await() }
        list.refresh()
        list.onNearTop(false)
        answer.complete(PageOf(listOf("both", "new"), null))
        list.loadMore()
        assertEquals(listOf("old", "both", "new"), list.items)
        assertFalse(list.stale)
        assertTrue(list.endReached)
    }

    @Test fun aPullReplacesAtOnceEvenFarDown() {
        val slot = Slot(stored = cached("old"))
        val answer = CompletableDeferred<PageOf<String>>()
        val list = cachedList(slot) { answer.await() }
        list.onNearTop(false)
        list.refresh(pullToRefresh = true)
        answer.complete(PageOf(listOf("new"), null))
        assertEquals(listOf("new"), list.items)
    }

    @Test fun anotherSlotDoesNotKeepTheOtherAccountsList() {
        var slot = Slot("one")
        var fail = false
        val list = PagedList(CoroutineScope(Dispatchers.Unconfined + Job()), { it }, cache = { slot }) {
            if (fail) throw offline else PageOf(listOf("mine"), null)
        }
        list.refresh()
        assertEquals(listOf("mine"), list.items)

        slot = Slot("two")
        fail = true
        list.refresh()
        assertTrue(list.items.isEmpty())
        assertFalse(list.stale)
        assertNull(list.loadedAt)
        assertEquals(offline, list.error)
    }

    @Test fun savingStopsOnceTheCacheIsFull() {
        val first = List(MAX_CACHED_ITEMS) { "v$it" }
        val pages = mapOf(null to PageOf(first, cursor("2")), "2" to PageOf(listOf("more"), null))
        val slot = Slot()
        val list = cachedList(slot) { pages.getValue(it?.id) }
        list.refresh()
        list.loadMore()
        assertEquals(MAX_CACHED_ITEMS + 1, list.items.size)
        assertEquals(1, slot.written.size)
    }

    @Test fun laterPagesAreSavedTooWhileThereIsRoom() {
        val pages = mapOf(null to PageOf(listOf("a"), cursor("2")), "2" to PageOf(listOf("b"), null))
        val slot = Slot()
        val list = cachedList(slot) { pages.getValue(it?.id) }
        list.refresh()
        list.loadMore()
        assertEquals(listOf(listOf("a"), listOf("a", "b")), slot.written)
    }
}
