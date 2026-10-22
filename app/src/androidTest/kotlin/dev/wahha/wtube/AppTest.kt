package dev.wahha.wtube

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.VideoItem
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks on a device or emulator with network: real YouTube data through the whole app.
 * Run with `./gradlew connectedDebugAndroidTest`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null

    private fun launch(intent: Intent? = null) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        scenario = ActivityScenario.launch(intent ?: Intent(context, MainActivity::class.java))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun tabsSwitchScreens() {
        launch()
        compose.onNodeWithText("Библиотека").performClick()
        compose.waitUntilExactlyOneExists(hasText("История"), UI_TIMEOUT)
        compose.onNodeWithText("Подписки").performClick()
        compose.waitUntilExactlyOneExists(hasText("Подписки появятся после входа"), UI_TIMEOUT)
        compose.onNodeWithText("Главная").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("home_feed"), UI_TIMEOUT)
    }

    /** "Clear" on the history page asks first; only the confirm button empties the history. */
    @Test
    fun clearingHistoryAsksFirst() {
        History.record(VideoItem("jNQXAC9IVRw", "Me at the zoo", null, "jawed", null, null, duration = 19, views = -1, uploaded = null, isLive = false))
        try {
            launch()
            compose.onNodeWithText("Библиотека").performClick()
            compose.waitUntilAtLeastOneExists(hasText("Все"), UI_TIMEOUT)
            compose.onAllNodesWithText("Все")[0].performClick()
            compose.waitUntilExactlyOneExists(hasText("Очистить"), UI_TIMEOUT)
            compose.onNodeWithText("Очистить").performClick()
            compose.waitUntilExactlyOneExists(hasTestTag("confirm_clear_history"), UI_TIMEOUT)
            assertTrue(History.entries.value.isNotEmpty())
            compose.onNodeWithTag("confirm_clear_history").performClick()
            compose.waitUntil(UI_TIMEOUT) { History.entries.value.isEmpty() }
        } finally {
            History.clear()
        }
    }

    @Test
    fun searchShowsResults() {
        launch()
        compose.onNodeWithTag("tab_search").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag("search_field"), UI_TIMEOUT)
        compose.onNodeWithTag("search_field").performTextInput("lofi girl")
        compose.onNodeWithTag("search_field").performImeAction()
        compose.waitUntil(LOAD_TIMEOUT) {
            compose.onAllNodesWithTag("search_results").fetchSemanticsNodes().firstOrNull()?.children?.isNotEmpty() == true
        }
    }

    /** A shared youtu.be link opens the watch page, the video really plays, and Home then recommends from it. */
    @Test
    fun deepLinkPlaysVideoAndSeedsHome() {
        launchVideo()

        // Back tucks the video into the mini player; the watched video seeds the home feed under it.
        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(UI_TIMEOUT) { sheetState() == "collapsed" }
        compose.waitUntil(LOAD_TIMEOUT) {
            compose.onAllNodesWithTag("home_feed").fetchSemanticsNodes().firstOrNull()?.children?.let { it.size > 3 } == true
        }
    }

    /** The watch page follows the finger: down to the mini player and back up, still playing; sideways closes it. */
    @Test
    fun playerSheetSwipes() {
        launchVideo()

        compose.onNodeWithTag("video_player").performTouchInput { swipeDown(startY = centerY, endY = centerY + 1200f, durationMillis = 250) }
        compose.waitUntil(UI_TIMEOUT) { sheetState() == "collapsed" }
        compose.waitUntilExactlyOneExists(hasTestTag("mini_player"), UI_TIMEOUT)
        compose.waitUntil(LOAD_TIMEOUT) { playerState() == "playing" }
        // The page under the sheet is the one it was opened over.
        compose.waitUntilAtLeastOneExists(hasTestTag("home_feed"), UI_TIMEOUT)

        compose.onNodeWithTag("mini_player").performTouchInput { swipeUp(startY = centerY, endY = centerY - 900f, durationMillis = 250) }
        compose.waitUntil(UI_TIMEOUT) { sheetState() == "expanded" }
        compose.waitUntilDoesNotExist(hasTestTag("mini_player"), UI_TIMEOUT)

        scenario?.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(UI_TIMEOUT) { sheetState() == "collapsed" }
        compose.waitUntilExactlyOneExists(hasTestTag("mini_player"), UI_TIMEOUT)
        compose.onNodeWithTag("mini_player").performTouchInput { swipeLeft(startX = centerX, endX = centerX - 900f, durationMillis = 200) }
        compose.waitUntilDoesNotExist(hasTestTag("player_sheet"), UI_TIMEOUT)
    }

    /** A long press plays at 2x while held, double tap seeks, and a speed picked in the ⚙ sheet sticks. */
    @Test
    fun playerGesturesAndSettings() {
        launchVideo()
        val video = compose.onNodeWithTag("video_player")

        // The test video is 18 s long: the long press goes first, while it surely still plays.
        // The long-press timeout runs on the test clock, which follows the injected event time.
        video.performTouchInput {
            down(Offset(width * 0.1f, centerY))
            advanceEventTime(1_000)
            moveBy(Offset(0f, 1f))
        }
        compose.waitUntilExactlyOneExists(hasText("2x"), UI_TIMEOUT)
        video.performTouchInput { up() }
        compose.waitUntilDoesNotExist(hasText("2x"), UI_TIMEOUT)

        video.performTouchInput { doubleClick(Offset(width * 0.8f, centerY)) }
        compose.waitUntilExactlyOneExists(hasText("+10 с"), UI_TIMEOUT)

        video.performTouchInput { click(center) }
        compose.waitUntilExactlyOneExists(hasContentDescription("Настройки воспроизведения"), UI_TIMEOUT)
        compose.onNodeWithContentDescription("Настройки воспроизведения").performClick()
        compose.onNodeWithText("Скорость").performClick()
        compose.onNodeWithText("1,5x").performClick()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntilExactlyOneExists(hasText("1,5x"), UI_TIMEOUT)
        compose.onNodeWithText("Субтитры").assertExists()
        Settings.set(Settings.Speed, 1f)
    }

    private fun launchVideo() {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/jNQXAC9IVRw")).setPackage(
            ApplicationProvider.getApplicationContext<android.content.Context>().packageName,
        ))
        compose.waitUntilExactlyOneExists(hasText("Me at the zoo"), LOAD_TIMEOUT)
        compose.waitUntil(LOAD_TIMEOUT) { playerState() == "playing" }
        compose.waitUntil(UI_TIMEOUT) { sheetState() == "expanded" }
    }

    private fun playerState(): String? = stateOf("video_player")

    private fun sheetState(): String? = stateOf("player_sheet")

    private fun stateOf(tag: String): String? = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)

    private companion object {
        const val UI_TIMEOUT = 5_000L
        const val LOAD_TIMEOUT = 30_000L
    }
}
