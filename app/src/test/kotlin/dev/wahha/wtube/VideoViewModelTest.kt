package dev.wahha.wtube

import dev.wahha.wtube.data.Comment
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.VideoDetails
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.innertube.Watch
import dev.wahha.wtube.ui.video.VideoSource
import dev.wahha.wtube.ui.video.VideoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.Page
import java.io.IOException

/** Opening videos on the player sheet: what is fetched, when, and what the sheet is left showing. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoViewModelTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private class FakeSource : VideoSource {
        val fetched = mutableListOf<String>()
        val failing = mutableSetOf<String>()

        override suspend fun video(id: String): VideoDetails {
            fetched += id
            if (id in failing) throw IOException("offline")
            val item = VideoItem(id, "title $id", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = false)
            return VideoDetails(item, "", likes = 1, subscribers = 1, uploadedAt = null, related = emptyList(), chapters = emptyList(), storyboard = emptyList())
        }

        override suspend fun watch(id: String) = Watch(state = null, commentsToken = null)
        override suspend fun comments(id: String, page: Page?) = PageOf<Comment>(emptyList(), null)
        override suspend fun dislikes(id: String) = 5L
    }

    private val source = FakeSource()
    private val vm = VideoViewModel(source)

    @Test
    fun openLoadsThePageAndAsksToPlay() {
        vm.open("a")
        assertEquals("a", vm.details?.item?.id)
        assertNull(vm.error)
        assertTrue(vm.playPending)
        assertEquals(5L, vm.dislikes)
        assertNotNull(vm.comments)
    }

    @Test
    fun adoptingThePlayingVideoDoesNotAskToPlay() {
        vm.open("a", play = false)
        assertFalse(vm.playPending)
        assertEquals("a", vm.details?.item?.id)
    }

    @Test
    fun failureShowsTheError() {
        source.failing += "a"
        vm.open("a")
        assertTrue(vm.error is IOException)
        assertNull(vm.details)
    }

    @Test
    fun openingTheSameVideoAgainDoesNotRefetch() {
        vm.open("a")
        vm.open("a")
        assertEquals(listOf("a"), source.fetched)
    }

    @Test
    fun openingAFailedVideoAgainRetries() {
        source.failing += "a"
        vm.open("a")
        source.failing.clear()
        vm.open("a")
        assertEquals(listOf("a", "a"), source.fetched)
        assertNull(vm.error)
        assertEquals("a", vm.details?.item?.id)
    }

    @Test
    fun anotherVideoReplacesThePageAndCanGoBack() {
        vm.open("a")
        vm.open("b")
        assertEquals("b", vm.details?.item?.id)
        assertTrue(vm.hasPrevious)
        vm.playPrevious()
        assertEquals("a", vm.id)
        assertFalse(vm.hasPrevious)
    }
}
