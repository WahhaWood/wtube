package dev.wahha.wtube.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class HiddenChannel(val url: String, val name: String, val avatar: String?)

/** "Not interested" videos and "Don't recommend" channels, newest first. */
@Serializable
data class HiddenItems(val videos: List<VideoItem> = emptyList(), val channels: List<HiddenChannel> = emptyList()) {
    private val videoIds by lazy { videos.mapTo(HashSet()) { it.id } }
    private val channelKeys by lazy { channels.mapTo(HashSet()) { channelKey(it.url) } }

    /** Whether [item] may be recommended: Home, search and related videos (and so autoplay) drop the rest. */
    fun allows(item: Item): Boolean =
        item !is VideoItem || (item.id !in videoIds && item.channelUrl?.let { channelKey(it) in channelKeys } != true)

    fun isHidden(channelUrl: String): Boolean = channelKey(channelUrl) in channelKeys
}

// "https://www.youtube.com/channel/UC…/" and "https://m.youtube.com/channel/UC…" are one channel.
private fun channelKey(url: String) = url.substringAfter("youtube.com").trimEnd('/')

/**
 * What the user asked not to be recommended, kept on the phone only: YouTube never learns of it, and
 * subscriptions, channel pages, playlists and history still show everything.
 */
object Hidden {
    private const val MAX_VIDEOS = 300
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(HiddenItems())
    private var file: File? = null

    val items: StateFlow<HiddenItems> = state.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, "hidden.json").also { file = it }
        scope.launch {
            val fromFile = runCatching { if (f.exists()) json.decodeFromString<HiddenItems>(f.readText()) else HiddenItems() }
                .onFailure { Log.e("Hidden", "Unreadable hidden list, starting fresh", it) }
                .getOrDefault(HiddenItems())
            state.update { current -> merge(current, fromFile) }
        }
    }

    fun hideVideo(video: VideoItem) = change { it.copy(videos = (listOf(video) + it.videos.filterNot { v -> v.id == video.id }).take(MAX_VIDEOS)) }

    fun showVideo(id: String) = change { it.copy(videos = it.videos.filterNot { v -> v.id == id }) }

    fun hideChannel(url: String, name: String, avatar: String?) =
        change { it.copy(channels = listOf(HiddenChannel(url, name, avatar)) + it.channels.filterNot { c -> channelKey(c.url) == channelKey(url) }) }

    fun showChannel(url: String) = change { it.copy(channels = it.channels.filterNot { c -> channelKey(c.url) == channelKey(url) }) }

    /** Everything at once (a restored backup). */
    fun replace(items: HiddenItems) = change { merge(items, HiddenItems()) }

    private fun merge(first: HiddenItems, second: HiddenItems) = HiddenItems(
        videos = (first.videos + second.videos).distinctBy { it.id }.take(MAX_VIDEOS),
        channels = (first.channels + second.channels).distinctBy { channelKey(it.url) },
    )

    private fun change(transform: (HiddenItems) -> HiddenItems) {
        state.update(transform)
        val snapshot = state.value
        val f = file ?: return
        scope.launch {
            runCatching {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(json.encodeToString(snapshot))
                tmp.renameTo(f)
            }.onFailure { Log.e("Hidden", "Could not save hidden list", it) }
        }
    }
}
