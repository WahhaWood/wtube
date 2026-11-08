package dev.wahha.wtube

import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.download.Download
import dev.wahha.wtube.download.DownloadState
import dev.wahha.wtube.download.afterRestart
import dev.wahha.wtube.download.arranged
import dev.wahha.wtube.download.nextInQueue
import dev.wahha.wtube.download.pausing
import dev.wahha.wtube.download.queueEnd
import dev.wahha.wtube.download.unpausing
import dev.wahha.wtube.download.waitingInOrder
import dev.wahha.wtube.download.withMoved
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadQueueTest {
    private fun video(id: String) = VideoItem(id, id, null, "Channel", null, null, 60, 100, null, false)

    private fun download(id: String, state: DownloadState = DownloadState.QUEUED, addedAt: Long = 0, order: Long = 0) =
        Download(video(id), audioOnly = false, quality = 720, file = "/$id.mp4", state = state, addedAt = addedAt, order = order)

    private fun List<Download>.ids() = map { it.video.id }

    @Test fun nextGoesByOrderThenByAddedAt() {
        val list = listOf(download("late", addedAt = 3), download("early", addedAt = 1), download("placed", addedAt = 9, order = -1))
        assertEquals("placed", list.nextInQueue()?.video?.id)
        assertEquals("early", list.filterNot { it.video.id == "placed" }.nextInQueue()?.video?.id)
    }

    @Test fun pausedSitsOutItsTurn() {
        val list = listOf(download("paused", DownloadState.PAUSED, addedAt = 1), download("queued", addedAt = 2))
        assertEquals("queued", list.nextInQueue()?.video?.id)
        assertNull(list.take(1).nextInQueue())
    }

    @Test fun pauseTakesOnlyWhatIsOnItsWay() {
        val list = listOf(
            download("running", DownloadState.RUNNING),
            download("queued"),
            download("done", DownloadState.DONE),
            download("failed", DownloadState.FAILED),
        )
        val states = list.pausing { true }.map { it.state }
        assertEquals(listOf(DownloadState.PAUSED, DownloadState.PAUSED, DownloadState.DONE, DownloadState.FAILED), states)
    }

    @Test fun pauseAndContinueOfOneLeaveTheRest() {
        val list = listOf(download("a"), download("b"))
        val paused = list.pausing { it.video.id == "a" }
        assertEquals(listOf(DownloadState.PAUSED, DownloadState.QUEUED), paused.map { it.state })
        assertEquals(list, paused.unpausing { it.video.id == "a" })
    }

    @Test fun continueAllLeavesFailedAlone() {
        val states = listOf(download("paused", DownloadState.PAUSED), download("failed", DownloadState.FAILED)).unpausing { true }.map { it.state }
        assertEquals(listOf(DownloadState.QUEUED, DownloadState.FAILED), states)
    }

    @Test fun restartRequeuesRunningAndKeepsPaused() {
        val states = listOf(download("running", DownloadState.RUNNING), download("paused", DownloadState.PAUSED)).afterRestart().map { it.state }
        assertEquals(listOf(DownloadState.QUEUED, DownloadState.PAUSED), states)
    }

    @Test fun movingPlacesAmongTheWaitingOnly() {
        val list = listOf(
            download("done", DownloadState.DONE),
            download("running", DownloadState.RUNNING),
            download("a", addedAt = 1),
            download("b", DownloadState.PAUSED, addedAt = 2),
            download("c", addedAt = 3),
        )
        assertEquals(listOf("c", "a", "b"), list.withMoved("c", "a").waitingInOrder().ids())
        assertEquals(listOf("b", "c", "a"), list.withMoved("a", null).waitingInOrder().ids())
        val after = list.withMoved("c", "a")
        assertEquals(DownloadState.PAUSED, after.first { it.video.id == "b" }.state)
        assertEquals(0L, after.first { it.video.id == "running" }.order)
        assertEquals(0L, after.first { it.video.id == "done" }.order)
    }

    @Test fun movingWhatDoesNotWaitChangesNothing() {
        val list = listOf(download("running", DownloadState.RUNNING), download("a"))
        assertEquals(list, list.withMoved("running", "a").map { it.copy(order = 0) })
    }

    @Test fun aNewDownloadJoinsTheEndOfAnArrangedQueue() {
        val arranged = listOf(download("a", addedAt = 1), download("b", addedAt = 2)).withMoved("b", "a")
        val added = download("new", addedAt = 3, order = arranged.queueEnd())
        assertEquals(listOf("b", "a", "new"), (listOf(added) + arranged).waitingInOrder().ids())
    }

    @Test fun rowsGoRunningThenWaitingInOrderThenTheRestAsTheyWere() {
        val list = listOf(
            download("done", DownloadState.DONE),
            download("second", addedAt = 2),
            download("failed", DownloadState.FAILED),
            download("first", DownloadState.PAUSED, addedAt = 1),
            download("running", DownloadState.RUNNING),
        )
        assertEquals(listOf("running", "first", "second", "done", "failed"), list.arranged().ids())
    }

    @Test fun fileFromBeforePausesAndOrderIsRead() {
        val old = """[{"video":{"id":"a","title":"A","thumbnail":null,"channelName":null,"channelUrl":null,"channelAvatar":null,"duration":60,"views":1,"uploaded":null,"isLive":false},"audioOnly":false,"quality":720,"file":"/a.mp4","state":"RUNNING","bytes":5,"total":9,"addedAt":7}]"""
        val read = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(Download.serializer()), old).single()
        assertEquals(DownloadState.RUNNING, read.state)
        assertEquals(0L, read.order)
        assertEquals(7L, read.addedAt)
        assertEquals(5L, read.bytes)
    }

    @Test fun orderAndPauseSurviveTheFile() {
        val json = Json { ignoreUnknownKeys = true }
        val saved = listOf(download("a", DownloadState.PAUSED, addedAt = 1, order = 4))
        assertEquals(saved, json.decodeFromString(ListSerializer(Download.serializer()), json.encodeToString(ListSerializer(Download.serializer()), saved)))
    }
}
