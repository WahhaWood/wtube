package dev.wahha.wtube.data

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import coil3.SingletonImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** A list as it was last loaded; [savedAt] is in epoch millis, [playlist] is the header of a playlist's list. */
class Cached<T>(val items: List<T>, val savedAt: Long, val playlist: PlaylistHead? = null)

/** Where one list keeps its last loaded items. [id] tells whose list it is: another id is another account's or another list's. */
interface ListSlot<T> {
    val id: String
    suspend fun read(): Cached<T>?
    fun write(items: List<T>)
}

/** The part of [PlaylistDetails] a cached playlist needs to show its header. */
@Serializable
class PlaylistHead(val name: String, val thumbnail: String?, val uploader: String?, val count: Long)

@Serializable
internal class StoredFeed(val version: Int, val savedAt: Long, val items: List<Item>, val playlist: PlaylistHead? = null)

/**
 * The files behind [FeedCache]: `<account>/<feed>.json` and `<account>/playlists/<hash>.json` under [root].
 * Blocking; a file that is broken or of another version is a miss, never a failure.
 */
internal class FeedFiles(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(account: String, feed: String): StoredFeed? {
        val file = File(root, "$account/$feed.json")
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(StoredFeed.serializer(), file.readText()) }
            .onFailure { Log.w("FeedCache", "Unreadable ${file.name} (${it.javaClass.simpleName})") }
            .getOrNull()
            ?.takeIf { it.version == VERSION }
    }

    fun write(account: String, feed: String, items: List<Item>, savedAt: Long, playlist: PlaylistHead? = null) {
        val file = File(root, "$account/$feed.json")
        file.parentFile?.mkdirs()
        writeAtomically(file, json.encodeToString(StoredFeed.serializer(), StoredFeed(VERSION, savedAt, items.take(MAX_CACHED_ITEMS), playlist)))
        if (feed.startsWith("$PLAYLISTS/")) {
            file.parentFile?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_PLAYLISTS)?.forEach { it.delete() }
        }
    }

    fun delete(account: String, feed: String) {
        File(root, "$account/$feed.json").delete()
    }

    /** Removes every account's lists but those of the session with [cookieKey]; the signed-out lists stay. */
    fun keepOnly(cookieKey: String?) {
        root.listFiles()?.filter { it.name != ANONYMOUS && (cookieKey == null || !it.name.startsWith("$cookieKey-")) }?.forEach { it.deleteRecursively() }
    }

    fun removeLeftovers() {
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
    }

    fun size(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
    }

    companion object {
        const val VERSION = 1
        const val MAX_PLAYLISTS = 20
        const val ANONYMOUS = "anonymous"
        const val PLAYLISTS = "playlists"
    }
}

/** What a list keeps on disk at most. */
const val MAX_CACHED_ITEMS = 100

/** The rows of a saved list whose pictures are fetched ahead, so that they are there offline without having been scrolled to. */
private const val ROWS_WITH_PICTURES = 30

/**
 * The last loaded feeds, so that they open without a network. Holds only what the screens loaded anyway and
 * asks for nothing itself. Lives in [Context.getFilesDir], outside [Backup]; each account has its own folder,
 * named by a hash, and leaving an account removes it.
 */
object FeedCache {
    private const val HOME = "home"
    // One at a time, in order: a later write of a list always lands last, and a purge cannot meet a write.
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private var files: FeedFiles? = null
    private var context: Context? = null

    fun init(context: Context) {
        this.context = context
        val files = FeedFiles(File(context.filesDir, "feeds")).also { files = it }
        scope.launch {
            files.removeLeftovers()
            Account.loaded.first { it }
            Account.session.map { it?.cookie }.distinctUntilChanged()
                .onEach { cookie -> files.keepOnly(cookie?.let(::hash)) }
                .launchIn(this)
            // The signed-out home is built from the history: it goes with the history.
            History.loaded.first { it }
            History.entries.map { it.isEmpty() }.distinctUntilChanged().filter { it }
                .onEach { files.delete(FeedFiles.ANONYMOUS, HOME) }
                .launchIn(this)
        }
    }

    /** The home feed: the account's or, signed out, the one built from the local history. */
    fun home(): ListSlot<Item>? = slot(accountKey(Account.active) ?: FeedFiles.ANONYMOUS, HOME)

    /** A list only an account has; null without a live session, so an expired one shows nothing as its own. */
    fun account(browseId: String): ListSlot<Item>? = accountKey(Account.active)?.let { slot(it, browseId) }

    /** A playlist as this account or, signed out, anybody sees it. [head] is read when the list is written. */
    fun playlist(url: String, head: () -> PlaylistHead?): ListSlot<Item>? =
        slot(accountKey(Account.active) ?: FeedFiles.ANONYMOUS, "${FeedFiles.PLAYLISTS}/${hash(url)}", head)

    suspend fun size(): Long = withContext(serial) { files?.size() ?: 0L }

    suspend fun clear() = withContext(serial) { files?.clear() ?: Unit }

    private fun slot(account: String, feed: String, head: () -> PlaylistHead? = { null }): ListSlot<Item>? {
        val files = files ?: return null
        return object : ListSlot<Item> {
            override val id = "$account/$feed"

            override suspend fun read(): Cached<Item>? = withContext(serial) {
                files.read(account, feed)?.let { Cached(it.items, it.savedAt, it.playlist) }
            }

            override fun write(items: List<Item>) {
                val playlist = head()
                val now = System.currentTimeMillis()
                scope.launch {
                    // An answer that arrives after the user left the account must not bring its folder back.
                    if (account != FeedFiles.ANONYMOUS && account != accountKey(Account.active)) return@launch
                    runCatching { files.write(account, feed, items, now, playlist) }
                        .onFailure { Log.w("FeedCache", "Could not save $feed (${it.javaClass.simpleName})") }
                    fetchPictures(items)
                }
            }
        }
    }

    /** Into the image loader's disk cache, by the same URLs the rows ask for. Not on a metered network: it is only for later. */
    private fun fetchPictures(items: List<Item>) {
        val context = context ?: return
        if (context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered != false) return
        val loader = SingletonImageLoader.get(context)
        items.take(ROWS_WITH_PICTURES).flatMap(::pictures).distinct().forEach { url ->
            val saved = loader.diskCache?.openSnapshot(url)?.use { true } ?: false
            if (!saved) loader.enqueue(ImageRequest.Builder(context).data(url).memoryCachePolicy(CachePolicy.DISABLED).size(Size(1, 1)).build())
        }
    }

    private fun pictures(item: Item): List<String> = when (item) {
        is VideoItem -> listOfNotNull(item.thumbnail, item.channelAvatar)
        is PlaylistItem -> listOfNotNull(item.thumbnail)
        is ChannelItem -> listOfNotNull(item.avatar)
    }

    private fun accountKey(session: Session?): String? =
        session?.let { hash(it.cookie) + "-" + hash("${it.authUser}/${it.pageId}") }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
