package dev.wahha.wtube

import android.content.ContextWrapper
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.HistoryEntry
import dev.wahha.wtube.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsonFileTest {
    @get:Rule val folder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true }

    private fun video(id: String) =
        VideoItem(id, "t", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = false)

    // Changes from many threads, the first of them before the file finished loading: the file must end
    // up with the latest state and keep everything that was on disk.
    @Test
    fun parallelChangesDuringLoadLoseNothing() {
        val dir = folder.newFolder()
        val file = File(dir, "history.json")
        val old = (1..200).map { HistoryEntry(video("old$it"), watchedAt = it.toLong()) }
        file.writeText(json.encodeToString(old))

        History.init(object : ContextWrapper(null) {
            override fun getFilesDir() = dir
        })
        runBlocking(Dispatchers.Default) {
            (1..100).map { launch { History.record(video("new$it")) } }.joinAll()
        }

        val deadline = System.currentTimeMillis() + 5_000
        var onDisk: List<HistoryEntry>? = null
        while (System.currentTimeMillis() < deadline) {
            onDisk = runCatching { json.decodeFromString<List<HistoryEntry>>(file.readText()) }.getOrNull()
            if (History.loaded.value && onDisk == History.entries.value) break
            Thread.sleep(50)
        }
        assertEquals(History.entries.value, onDisk)
        val ids = onDisk!!.mapTo(HashSet()) { it.video.id }
        assertTrue("old entries lost", (1..200).all { "old$it" in ids })
        assertTrue("new entries lost", (1..100).all { "new$it" in ids })
        assertTrue("temp files left", dir.list()!!.none { it.endsWith(".tmp") })
    }
}
