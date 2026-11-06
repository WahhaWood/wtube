package dev.wahha.wtube

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.player.Upcoming
import dev.wahha.wtube.ui.video.UpNextOverlay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The end screen's card says when the next video starts, and that it is on its way once the countdown is over. */
@RunWith(AndroidJUnit4::class)
class UpNextOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private val video = VideoItem("id", "Next", null, "c", null, null, duration = 60, views = 1, uploaded = null, isLive = false)

    private fun card(atMs: Long) {
        // The card's own ticker never lets the clock idle.
        compose.mainClock.autoAdvance = false
        compose.setContent { UpNextOverlay(Upcoming(video, atMs), onPlay = {}, onCancel = {}) }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test
    fun countdownShowsTheSeconds() {
        card(SystemClock.elapsedRealtime() + 5_000)
        compose.onNodeWithText("Через 5 с").assertExists()
    }

    @Test
    fun countdownOverShowsLoadingInsteadOfZero() {
        card(SystemClock.elapsedRealtime())
        compose.onNodeWithText("Загрузка").assertExists()
        compose.onNodeWithText("Через 0 с").assertDoesNotExist()
    }
}
