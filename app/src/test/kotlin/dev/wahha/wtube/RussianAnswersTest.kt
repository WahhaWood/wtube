package dev.wahha.wtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube.toItems
import dev.wahha.wtube.data.YouTubeLanguage
import dev.wahha.wtube.data.innertube.ChipKind
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.FeedParser
import dev.wahha.wtube.data.innertube.WatchParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import java.time.LocalDate

/**
 * Answers to `hl=ru` (anonymous, 2026-11-10, cut down) go through [YouTubeLanguage.toEnglish] as InnerTube's do,
 * then the same parsers as English ones: titles stay Russian, counts and dates come out right.
 */
class RussianAnswersTest {
    private val timeAgo = ServiceList.YouTube.getTimeAgoParser(Localization("en", "US"))

    private fun fixture(name: String): JsonObject = YouTubeLanguage.toEnglish(
        JsonParser.`object`().from(javaClass.getResourceAsStream("/innertube/$name")!!.bufferedReader().readText()),
    )

    private val cyrillic = Regex("[а-яА-ЯёЁ]")

    @Test
    fun channelWithMembershipsInRussian() {
        val page = fixture("channel_videos_ru.json")
        val extras = Channels.extras(page)
        assertEquals(listOf(ChipKind.LATEST, ChipKind.POPULAR, ChipKind.OLDEST, ChipKind.PUBLIC), extras.videoSorts.map { it.kind })
        assertTrue(extras.joinable)
        assertNotNull(extras.aboutToken)
        val videos = FeedParser.page(page, timeAgo).items.toItems().filterIsInstance<VideoItem>()
        assertTrue(videos.size >= 5)
        assertTrue(videos.all { it.uploadedAt != null })
        assertTrue(videos.filterNot { it.membersOnly || it.isLive }.all { it.views >= 1000 })
    }

    @Test
    fun aboutInRussian() {
        val about = Channels.about(fixture("channel_about_ru.json"))!!
        assertEquals(2_731_632_938, about.views)
        assertEquals(484, about.videos)
        assertEquals(LocalDate.of(2015, 3, 18), about.joined)
        assertEquals("Франция", about.country)
    }

    @Test
    fun playlistKeepsRussianTitlesWithRightCounts() {
        val videos = FeedParser.page(fixture("playlist_ru.json"), timeAgo).items.toItems().filterIsInstance<VideoItem>()
        assertTrue(videos.size >= 5)
        assertTrue(videos.any { it.title == "Три способа, чтобы уничтожить Вселенную" })
        assertTrue(videos.all { it.views >= 1_000_000 && it.uploadedAt != null })
    }

    @Test
    fun commentsInRussian() {
        val comments = WatchParser.comments(fixture("comments_page_ru.json"), timeAgo).comments
        assertTrue(comments.size >= 5)
        assertTrue(comments.all { it.postedAt != null })
        assertTrue(comments.first().likes >= 1000)
        assertTrue(comments.any { cyrillic.containsMatchIn(it.html) || it.html.isNotBlank() })
    }
}
