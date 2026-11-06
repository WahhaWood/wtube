package dev.wahha.wtube

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.innertube.WatchVideo
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.Navigator
import dev.wahha.wtube.ui.components.VideoCard
import dev.wahha.wtube.ui.video.MembersOnlyPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What a members-only video looks like: the mark on its card, the menu without what cannot work, its page. */
@RunWith(AndroidJUnit4::class)
class MembersOnlyUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private val nav = Navigator(
        showVideo = { _, _ -> },
        openChannel = { opened += it },
        openPlaylist = {},
        openSearch = {},
        openSettings = {},
        openLogin = {},
        openFeed = {},
        openDownloads = {},
        openHistory = {},
        openStats = {},
        openBypass = {},
        back = {},
    )

    private fun string(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private fun card(members: Boolean) {
        val video = VideoItem("id", "Title", null, "Channel", "https://www.youtube.com/channel/UCx", null, duration = 60, views = 1, uploaded = null, isLive = false, membersOnly = members)
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) { VideoCard(video) {} } }
    }

    @Test
    fun cardOfAMembersVideoHasTheMarkAndNoQueueOrDownload() {
        card(members = true)
        compose.onNodeWithText(string(R.string.members_only_badge)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.item_more)).performClick()
        compose.onNodeWithText(string(R.string.video_share)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.queue_add)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.queue_play_next)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.downloads_action)).assertDoesNotExist()
    }

    @Test
    fun cardOfAnOrdinaryVideoHasNoMarkAndAFullMenu() {
        card(members = false)
        compose.onNodeWithText(string(R.string.members_only_badge)).assertDoesNotExist()
        compose.onNodeWithContentDescription(string(R.string.item_more)).performClick()
        compose.onNodeWithText(string(R.string.queue_add)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.downloads_action)).assertIsDisplayed()
    }

    @Test
    fun pageNamesTheVideoAndOpensItsChannel() {
        val video = WatchVideo("Video title", "Channel", "https://www.youtube.com/channel/UCx")
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) { MembersOnlyPage("id", video) } }
        compose.onNodeWithText("Video title").assertIsDisplayed()
        compose.onNodeWithText(string(R.string.members_only_open)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.members_only_join)).assertIsDisplayed()
        compose.onNodeWithText("Channel").performClick()
        compose.runOnIdle { assertEquals(listOf("https://www.youtube.com/channel/UCx"), opened) }
    }

    // The watch page may not answer: the page still says what this is, with no way to a channel it does not know.
    @Test
    fun pageWithoutTheWatchPageStillExplains() {
        compose.setContent { CompositionLocalProvider(LocalNavigator provides nav) { MembersOnlyPage("id", null) } }
        compose.onNodeWithText(string(R.string.members_only_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.members_only_open)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.members_only_join)).assertDoesNotExist()
    }
}
