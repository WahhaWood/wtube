package dev.wahha.wtube

import dev.wahha.wtube.data.YouTubeLanguage
import dev.wahha.wtube.data.innertube.ChipKind
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.countryName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Russian answers of YouTube, as they came on 2026-11-10, put back into what the extractor reads. */
class YouTubeLanguageTest {
    private fun en(text: String) = YouTubeLanguage.phrase(text)

    @Test
    fun countsBecomeEnglish() {
        assertEquals("1.2M views", en("1,2 млн просмотров"))
        assertEquals("570K", en("570 тыс."))
        assertEquals("1.7K", en("1,7 тыс."))
        assertEquals("890892 views", en("890 892 просмотра"))
        assertEquals("2731632938 views", en("2 731 632 938 просмотров"))
        assertEquals("1 views", en("1 просмотр"))
        assertEquals("15.8M subscribers", en("15,8 млн подписчиков"))
        assertEquals("1.4M views", en("1,4 миллиона просмотров"))
        assertEquals("3B", en("3 млрд"))
        assertEquals("12K comments", en("12 тыс. комментариев"))
        assertEquals("484 videos", en("484 видео"))
        assertEquals("No views", en("Нет просмотров"))
    }

    @Test
    fun datesBecomeEnglish() {
        assertEquals("3 days ago", en("3 дня назад"))
        assertEquals("1 day ago", en("1 дн. назад"))
        assertEquals("3 months ago", en("3 мес. назад"))
        assertEquals("7 years ago", en("7 л. назад"))
        assertEquals("1 year ago", en("1 год назад"))
        assertEquals("3 hours ago", en("3 ч назад"))
        assertEquals("52 minutes ago", en("52 минуты назад"))
        assertEquals("2 weeks ago", en("2 недели назад"))
        assertEquals("Streamed 2 years ago", en("Трансляция закончилась 2 года назад"))
        assertEquals("6 years ago (edited)", en("6 лет назад (изменено)"))
    }

    @Test
    fun otherTextStays() {
        for (text in listOf("2015", "Текущее видео", "1,2 тыс. отметок \"Нравится\"", "3:33", "Новые", "18 лет и старше")) {
            assertEquals(text, en(text))
        }
    }

    @Test
    fun titlesAndCommentsStayAsWritten() {
        val json = """{"title":{"runs":[{"text":"10 лет назад"}]},"publishedTimeText":{"simpleText":"10 лет назад"},""" +
            """"properties":{"content":{"content":"5 дней назад"},"publishedTime":"5 дней назад"},"metadataParts":[{"text":{"content":"1,2 млн просмотров"}}]}"""
        val out = YouTubeLanguage.toEnglish(json)
        assertEquals(
            """{"title":{"runs":[{"text":"10 лет назад"}]},"publishedTimeText":{"simpleText":"10 years ago"},""" +
                """"properties":{"content":{"content":"5 дней назад"},"publishedTime":"5 days ago"},"metadataParts":[{"text":{"content":"1.2M views"}}]}""",
            out,
        )
        assertEquals("not json", YouTubeLanguage.toEnglish("not json"))
    }

    @Test
    fun joinedDateInBothLanguages() {
        assertEquals(LocalDate.of(2015, 3, 18), Channels.joinedDate("Joined Mar 18, 2015"))
        assertEquals(LocalDate.of(2015, 3, 18), Channels.joinedDate("Дата регистрации: 18 мар. 2015 г."))
        assertEquals(LocalDate.of(2009, 5, 2), Channels.joinedDate("Дата регистрации: 2 мая 2009 г."))
        assertNull(Channels.joinedDate("Joined recently"))
    }

    @Test
    fun countryFromEitherLanguage() {
        assertEquals("Франция", countryName("France", Locale("ru")))
        assertEquals("France", countryName("Франция", Locale.ENGLISH))
        assertEquals("Atlantis", countryName("Atlantis", Locale.ENGLISH))
    }

    @Test
    fun chipsToldApartByToken() {
        // Real tokens of a channel's Latest / Popular / Oldest chips; titles in Russian.
        val latest = "4qmFsgJkEhhVQzEwMW8tdlEyaU9qOXZyMDBKVWx5S3caSDhnWXVHaXg2S2tJb0NpUTJZak5tWTJFeU9TMHdNREF3TFRJMU16WXRPVEUwTkMwMU9ESTBNamxoWldNeU1qZ1lCQSUzRCUzRA%3D%3D"
        assertEquals(ChipKind.LATEST, Channels.kindOf("Новые", latest))
        assertEquals(ChipKind.POPULAR, Channels.kindOf("", Channels.popularOf(latest)!!))
        assertEquals(ChipKind.OLDEST, Channels.kindOf("", Channels.oldestOf(latest)!!))
        val members = "4qmFsgJ8EhhVQzEwMW8tdlEyaU9qOXZyMDBKVWx5S3caYDhnWkFHajU2UEVJNkNpUTJZak5tWTJFeU9TMHdNREF3TFRJMU16WXRPVEUwTkMwMU9ESTBNamxoWldNeU1qZ1lCQ0lRQ2c0S0Etb0JBQW9IR2dVS0E0SUJBQSUzRCUzRA%3D%3D"
        assertNull(Channels.kindOf("Только для спонсоров", members))
        assertEquals(ChipKind.PUBLIC, Channels.kindOf("Для всех", members))
    }
}
