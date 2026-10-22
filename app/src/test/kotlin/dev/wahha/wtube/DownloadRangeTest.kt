package dev.wahha.wtube

import dev.wahha.wtube.download.ExpiredException
import dev.wahha.wtube.download.downloadRange
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class DownloadRangeTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val data = ByteArray(10_000) { (it * 7).toByte() }
    private lateinit var target: File
    private val asked = mutableListOf<IntRange>()

    private fun request(url: String, from: Long, to: Long) =
        Request.Builder().url("$url?from=$from&to=$to").build()

    private fun serve(respond: (from: Int, to: Int, call: Int) -> MockResponse) {
        var call = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val from = request.requestUrl!!.queryParameter("from")!!.toInt()
                val to = request.requestUrl!!.queryParameter("to")!!.toInt()
                asked += from until to
                return respond(from, to, call++)
            }
        }
    }

    private fun slice(from: Int, to: Int) = MockResponse().setBody(okio.Buffer().write(data, from, to - from))

    @Before fun setUp() {
        server.start()
        target = File.createTempFile("part", ".video").also { it.delete() }
    }

    @After fun tearDown() {
        server.shutdown()
        target.delete()
    }

    private fun run(chunk: Long = 4_000, length: Long = data.size.toLong(), progress: (Long) -> Unit = {}) = runBlocking {
        downloadRange(client, server.url("/v").toString(), length, target, chunk, ::request, onProgress = { progress(it) })
    }

    @Test fun fetchesInChunksAndReportsProgress() {
        serve { from, to, _ -> slice(from, to) }
        val seen = mutableListOf<Long>()
        run(progress = { seen += it })
        assertArrayEquals(data, target.readBytes())
        assertEquals(listOf(0 until 4000, 4000 until 8000, 8000 until 10000), asked)
        assertEquals(listOf(4000L, 8000L, 10000L), seen)
    }

    @Test fun resumesFromWhatIsAlreadyOnDisk() {
        target.writeBytes(data.copyOf(5_000))
        serve { from, to, _ -> slice(from, to) }
        run()
        assertArrayEquals(data, target.readBytes())
        assertEquals(5_000, asked.first().first)
    }

    @Test fun staleLongerFileStartsOver() {
        target.writeBytes(ByteArray(20_000))
        serve { from, to, _ -> slice(from, to) }
        run()
        assertArrayEquals(data, target.readBytes())
        assertEquals(0, asked.first().first)
    }

    @Test fun expiredLinkIsTold() {
        serve { _, _, _ -> MockResponse().setResponseCode(403) }
        try { run(); fail() } catch (_: ExpiredException) {}
    }

    @Test fun emptyAnswerFailsInsteadOfLooping() {
        serve { _, _, _ -> MockResponse().setBody("") }
        try { run(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("no data")) }
        assertEquals(1, asked.size)
    }

    @Test fun cutConnectionKeepsPartAndRetryContinues() {
        serve { from, to, call ->
            if (call == 1) slice(from, to).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) else slice(from, to)
        }
        try { run(); fail() } catch (_: IOException) {}
        val kept = target.length()
        assertTrue("kept=$kept", kept >= 4_000 && kept < data.size)
        run()
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun serverErrorFails() {
        serve { _, _, _ -> MockResponse().setResponseCode(503) }
        try { run(); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("503")) }
    }

    @Test fun serverIgnoringTheRangeStillGivesTheRightBytes() {
        serve { _, _, _ -> MockResponse().setBody(okio.Buffer().write(data)) } // always the whole thing
        run(chunk = 4_000)
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun serverIgnoringTheRangeOnResumeStillGivesTheRightBytes() {
        target.writeBytes(data.copyOf(5_000))
        serve { _, _, _ -> MockResponse().setBody(okio.Buffer().write(data)) }
        run()
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun partialAnswerFromTheWrongPlaceFails() {
        target.writeBytes(data.copyOf(5_000))
        serve { _, to, _ ->
            slice(0, to - 5_000).setResponseCode(206).setHeader("Content-Range", "bytes 0-${to - 5_001}/${data.size}")
        }
        try { run(); fail() } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("Content-Range")) }
        assertArrayEquals(data.copyOf(5_000), target.readBytes())
    }

    @Test fun partialAnswerFromTheRightPlaceIsTaken() {
        serve { from, to, _ ->
            slice(from, to).setResponseCode(206).setHeader("Content-Range", "bytes $from-${to - 1}/${data.size}")
        }
        run()
        assertArrayEquals(data, target.readBytes())
    }

    @Test fun answerOfAnUnexpectedSizeFails() {
        target.writeBytes(data.copyOf(5_000))
        serve { _, _, _ -> slice(0, 1_234) }
        try { run(); fail() } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("1234")) }
        assertArrayEquals(data.copyOf(5_000), target.readBytes())
    }
}
