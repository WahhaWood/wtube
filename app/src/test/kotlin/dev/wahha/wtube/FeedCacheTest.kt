package dev.wahha.wtube

import android.content.ContextWrapper
import dev.wahha.wtube.data.Backup
import dev.wahha.wtube.data.ChannelItem
import dev.wahha.wtube.data.FeedCache
import dev.wahha.wtube.data.FeedFiles
import dev.wahha.wtube.data.HistoryEntry
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.MAX_CACHED_ITEMS
import dev.wahha.wtube.data.PlaylistHead
import dev.wahha.wtube.data.PlaylistItem
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeedCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun video(id: String) = VideoItem(id, "Title $id", null, "Channel", null, null, 60, 100, null, false)
    private val channel = ChannelItem("https://www.youtube.com/channel/UC1", "Channel", null, 5, true)
    private val playlist = PlaylistItem("https://www.youtube.com/playlist?list=PL1", "List", null, "Me", 3)

    private fun files() = FeedFiles(tmp.root)
    private fun file(path: String) = File(tmp.root, path)

    @Test fun everyKindOfRowComesBack() {
        val items = listOf(video("a").copy(membersOnly = true, upcoming = true), channel, playlist)
        files().write("acc", "home", items, savedAt = 42)
        val stored = files().read("acc", "home")!!
        assertEquals(items, stored.items)
        assertEquals(42L, stored.savedAt)
    }

    @Test fun aPlaylistKeepsItsHeader() {
        files().write("acc", "playlists/x", listOf(video("a")), 1, PlaylistHead("Name", "thumb", "Me", 7))
        val head = files().read("acc", "playlists/x")!!.playlist!!
        assertEquals(listOf("Name", "thumb", "Me"), listOf(head.name, head.thumbnail, head.uploader))
        assertEquals(7L, head.count)
    }

    @Test fun whatWasNeverSavedIsAMiss() = assertNull(files().read("acc", "home"))

    @Test fun aBrokenFileIsAMiss() {
        files().write("acc", "home", listOf(video("a")), 1)
        for (text in listOf("", "not json", "{\"version\":1,\"savedAt\":1,\"items\":[{\"type\":\"video\"", "{\"version\":1,\"savedAt\":1,\"items\":[{\"type\":\"future\",\"url\":\"u\"}]}")) {
            file("acc/home.json").writeText(text)
            assertNull("accepted: $text", files().read("acc", "home"))
        }
    }

    @Test fun anotherVersionIsAMiss() {
        file("acc").mkdirs()
        file("acc/home.json").writeText("""{"version":${FeedFiles.VERSION + 1},"savedAt":1,"items":[]}""")
        assertNull(files().read("acc", "home"))
    }

    @Test fun accountsDoNotSeeEachOther() {
        files().write("one", "home", listOf(video("a")), 1)
        assertNull(files().read("two", "home"))
        files().write("two", "home", listOf(video("b")), 1)
        assertEquals(listOf(video("a")), files().read("one", "home")!!.items)
    }

    @Test fun aListKeepsOnlyItsFirstRows() {
        files().write("acc", "home", List(MAX_CACHED_ITEMS + 20) { video("v$it") }, 1)
        val items = files().read("acc", "home")!!.items
        assertEquals(MAX_CACHED_ITEMS, items.size)
        assertEquals(video("v0"), items.first())
    }

    @Test fun theOldestPlaylistsGiveWay() {
        val extra = 3
        repeat(FeedFiles.MAX_PLAYLISTS + extra) { n ->
            files().write("acc", "${FeedFiles.PLAYLISTS}/p$n", listOf(video("v")), 1)
            // File times can be too coarse to order writes this close together.
            file("acc/${FeedFiles.PLAYLISTS}/p$n.json").setLastModified(1_000_000L + n * 1000)
        }
        val left = file("acc/${FeedFiles.PLAYLISTS}").list()!!.toSet()
        assertEquals(FeedFiles.MAX_PLAYLISTS, left.size)
        assertFalse("p0.json" in left)
        assertTrue("p${FeedFiles.MAX_PLAYLISTS + extra - 1}.json" in left)
    }

    @Test fun plainFeedsAreNotEvicted() {
        repeat(FeedFiles.MAX_PLAYLISTS + 3) { files().write("acc", "feed$it", listOf(video("v")), 1) }
        assertEquals(FeedFiles.MAX_PLAYLISTS + 3, file("acc").list()!!.size)
    }

    @Test fun leavingAnAccountRemovesItsFolderOnly() {
        files().write("aaa-1", "home", listOf(video("a")), 1)
        files().write("aaa-2", "home", listOf(video("a")), 1)
        files().write("bbb-1", "home", listOf(video("b")), 1)
        files().write(FeedFiles.ANONYMOUS, "home", listOf(video("c")), 1)

        // The identities of one session stay together: they share its cookie.
        files().keepOnly("aaa")
        assertEquals(setOf("aaa-1", "aaa-2", FeedFiles.ANONYMOUS), tmp.root.list()!!.toSet())

        files().keepOnly(null)
        assertEquals(setOf(FeedFiles.ANONYMOUS), tmp.root.list()!!.toSet())
    }

    @Test fun clearEmptiesAndWritingWorksAgain() {
        files().write("acc", "home", listOf(video("a")), 1)
        assertTrue(files().size() > 0)
        files().clear()
        assertEquals(0L, files().size())
        assertNull(files().read("acc", "home"))
        files().write("acc", "home", listOf(video("a")), 1)
        assertNotNull(files().read("acc", "home"))
    }

    @Test fun strayTempFilesAreRemoved() {
        files().write("acc", "home", listOf(video("a")), 1)
        val stray = file("acc/home.json.123.tmp").apply { writeText("half") }
        files().removeLeftovers()
        assertFalse(stray.exists())
        assertNotNull(files().read("acc", "home"))
    }

    // The history keeps a list of videos, no list of any Item: making Item polymorphic must not add a type to it.
    @Test fun theHistoryFormatDidNotChange() {
        val old = """[{"video":{"id":"jNQXAC9IVRw","title":"Me at the zoo","thumbnail":null,"channelName":"jawed",
            "channelUrl":null,"channelAvatar":null,"duration":19,"views":-1,"uploaded":null,"isLive":false},
            "watchedAt":1,"positionMs":5000}]"""
        val json = Json { ignoreUnknownKeys = true }
        val serializer = ListSerializer(HistoryEntry.serializer())
        val entries = json.decodeFromString(serializer, old)
        assertEquals("jNQXAC9IVRw", entries.single().video.id)
        val written = json.encodeToString(serializer, entries)
        assertFalse(written, "\"type\"" in written)
        assertEquals(entries, json.decodeFromString(serializer, written))
    }

    @Test fun aBackupHasNoFeedsAndNoSeenLog() {
        val keys = Json.parseToJsonElement(Backup.current()).jsonObject.keys
        assertEquals(setOf("settings", "history", "searches", "hidden", "stats"), keys - "version")
    }
}

/** [FeedCache] against the session: a list belongs to an account, not to whoever is signed in when it arrives. */
class FeedCacheAccountTest {
    @get:Rule val tmp = TemporaryFolder()

    private val video = VideoItem("a", "T", null, "C", null, null, 60, 100, null, false)
    private val items = listOf<Item>(video)

    private fun init() = FeedCache.init(object : ContextWrapper(null) { override fun getFilesDir() = tmp.root })

    private suspend fun signedIn(session: Session?) {
        Account.save(session)
        delay(100)
    }

    @After fun signOut() = runBlocking { Account.save(null) }

    @Test fun anAnswerAfterLeavingTheAccountIsNotSaved() = runBlocking {
        init()
        signedIn(Session(cookie = "SID=a"))
        val slot = FeedCache.home()!!
        signedIn(Session(cookie = "SID=b"))
        slot.write(items)
        delay(100)
        assertNull(slot.read())
        assertFalse(File(tmp.root, slot.id.substringBefore('/')).exists())
    }

    @Test fun anExpiredSessionHasNoAccountLists() = runBlocking {
        init()
        signedIn(Session(cookie = "SID=a"))
        val live = FeedCache.home()!!
        assertNotNull(FeedCache.account("FEsubscriptions"))

        signedIn(Session(cookie = "SID=a", expired = true))
        assertNull(FeedCache.account("FEsubscriptions"))
        // Home goes on as signed out: another list than the account's.
        assertNotEquals(live.id, FeedCache.home()!!.id)
    }

    @Test fun identitiesAndAccountsHaveTheirOwnLists() = runBlocking {
        init()
        signedIn(Session(cookie = "SID=a"))
        val first = FeedCache.home()!!
        first.write(items)
        delay(100)
        assertEquals(items, first.read()!!.items)

        signedIn(Session(cookie = "SID=a", authUser = 1))
        val second = FeedCache.home()!!
        assertNotEquals(first.id, second.id)
        assertNull(second.read())
    }

    @Test fun noFileNameHoldsTheCookie() = runBlocking {
        init()
        signedIn(Session(cookie = "SID=secret"))
        FeedCache.home()!!.write(items)
        delay(100)
        val names = tmp.root.walkTopDown().map { it.name }.toList()
        assertTrue(names.size > 1)
        assertFalse(names.toString(), names.any { "secret" in it })
    }
}
