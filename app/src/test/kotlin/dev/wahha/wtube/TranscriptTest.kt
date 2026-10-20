package dev.wahha.wtube

import dev.wahha.wtube.data.CaptionTrack
import dev.wahha.wtube.data.Transcript
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptTest {
    // An auto-generated json3 track as YouTube sends it: a window setup event, word segments with offsets,
    // and line-break events that carry no words.
    private val autoJson3 = """
        {"wireMagic":"pb3","events":[
          {"tStartMs":0,"dDurationMs":212000,"id":1,"wpWinPosId":1,"wsWinStyleId":1},
          {"tStartMs":160,"dDurationMs":4120,"wWinId":1,"segs":[{"utf8":"we're","acAsrConf":0},{"utf8":" no","tOffsetMs":400},{"utf8":" strangers","tOffsetMs":800}]},
          {"tStartMs":2270,"dDurationMs":2010,"wWinId":1,"aAppend":1,"segs":[{"utf8":"\n"}]},
          {"tStartMs":2280,"dDurationMs":3000,"wWinId":1,"segs":[{"utf8":"to\nlove"}]}
        ]}
    """.trimIndent()

    @Test
    fun json3LinesWithoutBreakEvents() {
        val lines = Transcript.parse(autoJson3)
        assertEquals(listOf(160L, 2280L), lines.map { it.startMs })
        assertEquals(listOf("we're no strangers", "to love"), lines.map { it.text })
    }

    @Test
    fun preferredTrackIsHumanInLanguageThenSpokenThenAuto() {
        val autoEn = CaptionTrack("a", "en", auto = true)
        val de = CaptionTrack("b", "de", auto = false)
        val en = CaptionTrack("c", "en-GB", auto = false)
        val ru = CaptionTrack("d", "ru", auto = false)
        assertEquals(ru, Transcript.preferred(listOf(autoEn, de, en, ru), "ru"))
        assertEquals(en, Transcript.preferred(listOf(autoEn, de, en), "ru"))
        assertEquals(autoEn, Transcript.preferred(listOf(de, autoEn), "ru"))
        assertEquals(de, Transcript.preferred(listOf(de), "ru"))
        assertEquals(null, Transcript.preferred(emptyList(), "ru"))
    }
}
