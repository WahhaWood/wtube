package dev.wahha.wtube

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.download.Download
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.download.Downloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real downloads over the network; the app is on screen so the foreground service may start. */
@RunWith(AndroidJUnit4::class)
class DownloadsTest {
    private val video = VideoItem("aqz-KE-bpKQ", "Big Buck Bunny", null, null, null, null, 635, -1, null, false)
    private val key = Download.key(video.id, audioOnly = true)
    private val scenario = ActivityScenario.launch<MainActivity>(
        Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
    )

    @After fun tearDown() {
        Downloads.remove(key)
        scenario.close()
    }

    private suspend fun waitFor(timeout: Long, check: (Download?) -> Boolean): Download? =
        withTimeout(timeout) { Downloads.entries.first { list -> check(list.firstOrNull { it.key == key }) } }.firstOrNull { it.key == key }

    /** Queueing the same video again while it downloads starts it over and finishes it whole; the queue never sticks. */
    @Test fun queueingAgainWhileRunningStartsOver() = runBlocking {
        Downloads.remove(key)
        Downloads.enqueue(video, audioOnly = true, quality = 0)
        waitFor(60_000) { it?.state == DownloadState.RUNNING && it.bytes > 0 && it.bytes < it.total }
        Downloads.enqueue(video, audioOnly = true, quality = 0)
        val done = waitFor(180_000) { it?.state == DownloadState.DONE || it?.state == DownloadState.FAILED }
        assertEquals(done?.error, DownloadState.DONE, done?.state)
        val file = File(done!!.file)
        assertEquals(done.total, file.length())
        Downloads.partsOf(done).drop(1).forEach { assertEquals("leftover $it", false, it.exists()) }
        assertNotNull(Downloads.finished(video.id, audioOnly = true))
    }

    /** Paused mid-way it stops with its part kept, is not a failure, and continues from there into a whole file. */
    @Test fun pauseStopsKeepsThePartAndContinues() = runBlocking {
        Downloads.remove(key)
        Downloads.enqueue(video, audioOnly = true, quality = 0)
        val running = waitFor(60_000) { it?.state == DownloadState.RUNNING && it.bytes > 0 && it.bytes < it.total }!!
        Downloads.pause(key)
        val part = Downloads.partsOf(running)[2]
        // Whatever request was under way stops at its next buffer.
        delay(1_500)
        val kept = part.length()
        assertTrue("part kept: $kept", kept > 0)
        delay(2_000)
        assertEquals("still growing while paused", kept, part.length())
        assertEquals(DownloadState.PAUSED, Downloads.entries.value.first { it.key == key }.state)

        Downloads.unpause(key)
        val done = waitFor(180_000) { it?.state == DownloadState.DONE || it?.state == DownloadState.FAILED }
        assertEquals(done?.error, DownloadState.DONE, done?.state)
        assertEquals(done!!.total, File(done.file).length())
        assertFalse("leftover part", part.exists())
    }
}
