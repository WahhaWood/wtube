package dev.wahha.wtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.data.innertube.InnerTubeFormatException
import dev.wahha.wtube.data.innertube.Shorts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Shorts feed's two answers, live anonymous ones cut down (2026-11-10). */
class ShortsTest {
    private fun fixture(name: String): JsonObject =
        JsonParser.`object`().from(javaClass.getResourceAsStream("/innertube/$name")!!.bufferedReader().readText())

    @Test
    fun seedlessStartGivesTheFirstShortAndTheSequenceToken() {
        val page = Shorts.first(fixture("shorts_seedless.json"))
        assertEquals(listOf("SlH6ankaZ8c"), page.items.map { it.id })
        assertEquals("https://i.ytimg.com/vi/SlH6ankaZ8c/frame0.jpg", page.items.single().thumbnail)
        val next = page.next!!
        assertTrue(Feeds.isCursor(next))
        assertTrue(next.id.startsWith("GmYKC1NsSDZhbmthWjhj"))
    }

    @Test
    fun sequenceGivesABatchAndTheNextToken() {
        val page = Shorts.sequence(fixture("shorts_sequence.json"))
        assertEquals(
            listOf("9WzRmCR_i3c", "IMXVNDrKXI0", "f5vlDbkVjog", "Dfp17ZA1udE", "tfgbu0Z4ncc", "0BZ1C83I2R4", "yrspHAY0SEk", "bZoR8A2znpo"),
            page.items.map { it.id },
        )
        assertTrue(page.items.all { it.thumbnail?.endsWith("/frame0.jpg") == true })
        assertTrue(Feeds.isCursor(page.next!!))
    }

    @Test
    fun theEndOfTheSequenceHasNoNextPage() {
        val page = Shorts.sequence(JsonParser.`object`().from("""{"entries":[]}"""))
        assertTrue(page.items.isEmpty())
        assertNull(page.next)
    }

    @Test(expected = InnerTubeFormatException::class)
    fun aStartWithoutAShortIsAnError() {
        Shorts.first(JsonParser.`object`().from("""{"sequenceContinuation":"x"}"""))
    }
}
