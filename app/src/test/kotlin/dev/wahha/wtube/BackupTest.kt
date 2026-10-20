package dev.wahha.wtube

import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.Backup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    @Test
    fun currentStateRoundTrips() {
        val backup = Backup.parse(Backup.current())
        assertEquals(AppSettings(), backup.settings)
        assertEquals(Backup.VERSION, backup.version)
    }

    // A file from an older or newer version: missing settings take defaults, unknown ones are ignored.
    @Test
    fun otherVersionsStillRestore() {
        val backup = Backup.parse(
            """
            {"version":1,"settings":{"maxQuality":720,"speed":1.5,"someFutureSetting":true},
             "history":[{"video":{"id":"jNQXAC9IVRw","title":"Me at the zoo","thumbnail":null,"channelName":"jawed",
               "channelUrl":null,"channelAvatar":null,"duration":19,"views":-1,"uploaded":null,"isLive":false},
               "watchedAt":1,"positionMs":5000}],
             "extra":"ignored"}
            """,
        )
        assertEquals(720, backup.settings.maxQuality)
        assertEquals(1.5f, backup.settings.speed)
        assertEquals(AppSettings().sponsorCategories, backup.settings.sponsorCategories)
        assertEquals(5000L, backup.history.single().positionMs)
        assertTrue(backup.searches.isEmpty())
    }
}
