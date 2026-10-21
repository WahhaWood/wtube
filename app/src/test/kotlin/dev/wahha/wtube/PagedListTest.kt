package dev.wahha.wtube

import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.ui.components.PagedList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.Page

class PagedListTest {
    private fun cursor(id: String) = Page("wtube:test", id)

    private fun list(load: suspend (Page?) -> PageOf<String>) =
        PagedList(CoroutineScope(Dispatchers.Unconfined + Job()), { it }, load)

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
}
