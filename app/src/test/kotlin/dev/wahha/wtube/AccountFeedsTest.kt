package dev.wahha.wtube

import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.ui.library.LibraryViewModel
import dev.wahha.wtube.ui.subscriptions.SubscriptionsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** A feed answer for the account the user just switched away from must not show up under the new one. */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountFeedsTest {
    private val a = Session(cookie = "SID=a")
    private val b = Session(cookie = "SID=a", authUser = 1)

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun playlist(name: String) = PlaylistItem("https://www.youtube.com/playlist?list=$name", name, null, null, 1)

    private fun channel(name: String) = ChannelItem("https://www.youtube.com/channel/$name", name, null, 0, false)

    @Test fun libraryDropsPlaylistsOfThePreviousAccount() {
        val answers = mutableListOf<CompletableDeferred<List<PlaylistItem>>>()
        val vm = LibraryViewModel { CompletableDeferred<List<PlaylistItem>>().also { answers += it }.await() }
        vm.sync(a)
        vm.sync(b)
        answers[1].complete(listOf(playlist("b")))
        answers[0].complete(listOf(playlist("a")))
        assertEquals(listOf(playlist("b")), vm.playlists)
    }

    @Test fun subscriptionsDropChannelsOfThePreviousAccount() {
        val channels = mutableListOf<CompletableDeferred<ItemsPage>>()
        val vm = SubscriptionsViewModel { browseId, _ ->
            if (browseId == Feeds.CHANNELS) CompletableDeferred<ItemsPage>().also { channels += it }.await()
            else PageOf(emptyList<Item>(), null)
        }
        vm.sync(a)
        vm.sync(b)
        channels[1].complete(PageOf(listOf(channel("b")), null))
        channels[0].complete(PageOf(listOf(channel("a")), null))
        assertEquals(listOf(channel("b")), vm.channels)
    }
}
