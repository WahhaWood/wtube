package dev.wahha.wtube

import dev.wahha.wtube.player.translateVtt
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTranslationTest {
    private val vtt = """
        WEBVTT
        Kind: captions
        Language: en

        00:00:01.200 --> 00:00:03.360
        All right, so here we are, in front of the
        elephants

        00:00:03.360 --> 00:00:05.000 align:start
        <00:00:03.500><c> Tom &amp; Jerry</c>

        00:00:05.000 --> 00:00:06.000
        elephants
    """.trimIndent()

    @Test
    fun keepsTimingsAndTranslatesEachDistinctTextOnce() {
        val asked = mutableListOf<String>()
        val out = translateVtt(vtt) { batch -> asked += batch; batch.map { "RU:$it" } }
        assertEquals(listOf("All right, so here we are, in front of the elephants", "Tom & Jerry", "elephants"), asked)
        assertEquals(
            """
            WEBVTT
            Kind: captions
            Language: en

            00:00:01.200 --> 00:00:03.360
            RU:All right, so here we are, in front of the elephants

            00:00:03.360 --> 00:00:05.000 align:start
            RU:Tom &amp; Jerry

            00:00:05.000 --> 00:00:06.000
            RU:elephants
            """.trimIndent() + "\n",
            out,
        )
    }
}
