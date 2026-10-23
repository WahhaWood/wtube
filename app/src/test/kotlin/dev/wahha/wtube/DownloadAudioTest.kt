package dev.wahha.wtube

import dev.wahha.wtube.download.AudioChoice
import dev.wahha.wtube.download.bestAudio
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod

class DownloadAudioTest {
    private fun audio(itag: Int, language: String, type: AudioTrackType, bitrate: Int = 128): AudioStream {
        val item = ItagItem.getItag(itag).apply { contentLength = 1000 }
        return AudioStream.Builder()
            .setId("$itag-$language")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=$itag&l=$language", true)
            .setMediaFormat(item.mediaFormat)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setAverageBitrate(bitrate)
            .setAudioTrackType(type)
            .setAudioTrackId("$language.4")
            .setAudioLocale(Locale.forLanguageTag(language))
            .setItagItem(item)
            .build()
    }

    private val streams = listOf(
        audio(140, "en", AudioTrackType.ORIGINAL),
        audio(140, "ru", AudioTrackType.DUBBED),
    )

    @Test
    fun noPickSavesTheOriginal() {
        assertEquals("en", bestAudio(streams)?.audioLocale?.language)
    }

    @Test
    fun pickedDubIsSaved() {
        assertEquals("ru", bestAudio(streams, AudioChoice("ru", "DUBBED"))?.audioLocale?.language)
    }

    @Test
    fun regionalTagStillMatches() {
        assertEquals("ru", bestAudio(streams, AudioChoice("ru-RU", null))?.audioLocale?.language)
    }

    @Test
    fun missingPickFallsBackToTheOriginal() {
        assertEquals("en", bestAudio(streams, AudioChoice("de", "DUBBED"))?.audioLocale?.language)
    }
}
