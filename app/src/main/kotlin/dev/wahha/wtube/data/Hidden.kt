package dev.wahha.wtube.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
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
    private val state = MutableStateFlow(HiddenItems())
    private val disk = JsonFile("Hidden", HiddenItems.serializer()) { state.value }

    val items: StateFlow<HiddenItems> = state.asStateFlow()

    fun init(context: Context) {
        disk.load(File(context.filesDir, "hidden.json")) { fromFile ->
            state.update { current -> merge(current, fromFile ?: HiddenItems()) }
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
        disk.save()
    }
}
