package dev.wahha.wtube

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.download.Download
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.download.Downloads
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
