package dev.wahha.wtube

import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.player.PlayQueue
import dev.wahha.wtube.player.SavedSleep
import dev.wahha.wtube.player.Sleep
import dev.wahha.wtube.player.mayPlayUpcoming
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The sleep timer against the next video of the end screen's countdown (or of the queue). */
class AutoplayTest {
    @Test
    fun noTimerPlays() {
        assertTrue(mayPlayUpcoming(1_000, null))
    }

    @Test
    fun timerDueDuringTheCountdownStopsIt() {
        assertFalse(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 5_000, endOfVideo = false)))
        // Woken together after a doze: same moment, the timer wins.
        assertFalse(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 8_000, endOfVideo = false)))
    }

    @Test
    fun timerDueLaterLetsItPlay() {
        assertTrue(mayPlayUpcoming(upcomingAtMs = 8_000, sleep = Sleep(atMs = 8_001, endOfVideo = false)))
    }

    // "End of video" stops before any next video is scheduled; a "next" pressed by hand still plays.
    @Test
    fun endOfVideoTimerDoesNotBlockNext() {
        assertTrue(mayPlayUpcoming(8_000, Sleep(atMs = null, endOfVideo = true)))
    }

    private val written = SavedSleep(atMs = 1_000_000, endOfVideo = false, elapsedMs = 400_000, wallMs = 5_000_000)

    @Test
    fun timerGoesOnAfterTheProcessDied() {
        val sleep = written.restore(elapsedNow = 500_000, wallNow = 5_100_000)
        assertEquals(1_000_000L, sleep?.atMs)
    }

    @Test
    fun timerWhoseTimePassedIsDropped() {
        assertNull(written.restore(elapsedNow = 1_000_000, wallNow = 5_600_000))
    }

    // The elapsed clock starts over: its 1 000 000 would mean something else now.
    @Test
    fun timerIsDroppedAfterAReboot() {
        assertNull(written.restore(elapsedNow = 500_000, wallNow = 9_000_000))
        assertNull(SavedSleep(null, endOfVideo = true, 400_000, 5_000_000).restore(500_000, 9_000_000))
    }

    @Test
    fun endOfVideoTimerGoesOnWithoutATime() {
        val sleep = SavedSleep(null, endOfVideo = true, 400_000, 5_000_000).restore(500_000, 5_100_000)
        assertTrue(sleep?.endOfVideo == true)
    }

    @Test
    fun queueComesBackFromItsFileAndABrokenFileGivesAnEmptyOne() {
        fun video(id: String) = VideoItem(id, "t", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = false)
        fun awaitQueue(expected: List<String>) {
            val deadline = System.currentTimeMillis() + 5_000
            while (PlayQueue.items.value.map { it.id } != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(expected, PlayQueue.items.value.map { it.id })
        }
        val file = File.createTempFile("queue", ".json").apply { deleteOnExit() }
        file.writeText("not json{")
        PlayQueue.forget()
        PlayQueue.load(file)
        awaitQueue(emptyList())
        PlayQueue.add(video("a"))
        PlayQueue.add(video("b"))
        val deadline = System.currentTimeMillis() + 5_000
        while (!file.readText().contains("\"b\"") && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue(file.readText().contains("\"b\""))

        PlayQueue.forget()
        assertFalse(file.exists())

        val saved = File.createTempFile("queue", ".json").apply { deleteOnExit() }
        saved.writeText(Json.encodeToString(ListSerializer(VideoItem.serializer()), listOf(video("x"), video("y"))))
        PlayQueue.load(saved)
        awaitQueue(listOf("x", "y"))
        PlayQueue.forget()
    }
}
