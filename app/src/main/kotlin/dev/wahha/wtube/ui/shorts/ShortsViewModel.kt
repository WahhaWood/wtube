package dev.wahha.wtube.ui.shorts

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.wahha.wtube.data.Dislikes
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.VideoDetails
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.account.Session
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.LikeStatus
import dev.wahha.wtube.data.innertube.ShortItem
import dev.wahha.wtube.data.innertube.Shorts
import dev.wahha.wtube.data.innertube.VideoState
import dev.wahha.wtube.data.innertube.Watch
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.launchAction
import dev.wahha.wtube.ui.video.VideoComments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The Shorts tab: the feed, the short on screen and the pages of the shorts around it. */
class ShortsViewModel : ViewModel() {
    private var loadedFor: Any? = Unloaded

    val feed = PagedList(viewModelScope, ShortItem::id) { page ->
        val loaded = Shorts.page(page)
        val hidden = Hidden.items.value.videos.mapTo(HashSet()) { it.id }
        PageOf(loaded.items.filter { it.id !in hidden }, loaded.next)
    }

    /** Index of the short on screen in [PagedList.items] of [feed]. */
    var current by mutableIntStateOf(0)
        private set

    /** Sound off for every short, until switched back on; not a setting. */
    var muted by mutableStateOf(false)

    // Only around [current]: a page holds the whole video page, related videos included. Not state: the
    // pages are looked up while composing, and each one's own fields are state.
    private val pages = HashMap<String, ShortPage>()

    /** The feed of the account [session] signed in, loaded again when the account changes. */
    fun sync(session: Session?) {
        val key = session?.let { listOf(it.cookie, it.authUser, it.pageId, it.expired) }
        if (key == loadedFor) return
        loadedFor = key
        reload()
    }

    /** A fresh feed from the start. */
    fun reload() {
        pages.clear()
        current = 0
        feed.refresh()
    }

    /** The short at [index] of [items] (the feed as shown) is on screen: its neighbours load, far pages go, the feed grows near its end. */
    fun select(index: Int, items: List<ShortItem>) {
        current = index
        val near = (index - 1..index + 1).mapNotNull(items::getOrNull)
        near.forEach { page(it).load() }
        val keep = (index - KEEP..index + KEEP).mapNotNullTo(HashSet()) { items.getOrNull(it)?.id }
        pages.keys.retainAll(keep)
        if (index >= items.size - LOAD_AHEAD) feed.loadMore()
    }

    /** The page of short [id] if it is around, without making one. */
    fun known(id: String): ShortPage? = pages[id]

    fun page(item: ShortItem): ShortPage = pages.getOrPut(item.id) { ShortPage(item, viewModelScope) }

    private object Unloaded

    private companion object {
        const val KEEP = 4
        const val LOAD_AHEAD = 3
    }
}

/** One short's page: what [YouTube.video] and the watch page say of it, and the account's rating and subscription. */
@Stable
class ShortPage(val item: ShortItem, private val scope: CoroutineScope) {
    var details by mutableStateOf<VideoDetails?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set

    /** The signed-in account's rating and subscription; null when signed out or YouTube did not answer. */
    var state by mutableStateOf<VideoState?>(null)
        private set

    /** Return YouTube Dislike's estimate; -1 when off or unknown. */
    var dislikes by mutableLongStateOf(-1L)
        private set

    private var job: Job? = null
    private var watch: Deferred<Watch?>? = null
    private var ratedBefore = LikeStatus.INDIFFERENT

    /** Loaded only when the sheet opens: most shorts are swiped past. */
    val comments = VideoComments(scope, firstToken = { watch?.await()?.commentsToken }, extractor = { YouTube.comments(item.id, it) })

    fun load() {
        if (details != null || job?.isActive == true) return
        error = null
        job = scope.launch {
            try {
                val watch = async { runCatching { Actions.watch(item.id) }.getOrNull() }
                this@ShortPage.watch = watch
                if (Settings.current.value.showDislikes) launch { runCatching { Dislikes.of(item.id) }.onSuccess { dislikes = it } }
                val details = YouTube.video(item.id)
                this@ShortPage.details = details
                watch.await()?.state?.let { s ->
                    ratedBefore = s.like
                    state = if (s.channelId != null) s else s.copy(channelId = details.item.channelUrl?.substringAfter("/channel/", "")?.ifEmpty { null })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e
            }
        }
    }

    /** The like count with the rating given here applied; -1 when hidden. */
    val likes: Long
        get() {
            val likes = details?.likes ?: return -1
            if (likes < 0) return likes
            val now = state?.like ?: return likes
            return likes + (if (now == LikeStatus.LIKE) 1 else 0) - (if (ratedBefore == LikeStatus.LIKE) 1 else 0)
        }

    /** Return YouTube Dislike's count with the rating given here applied, as [likes]. */
    val dislikesShown: Long
        get() {
            if (dislikes < 0) return dislikes
            val now = state?.like ?: return dislikes
            return dislikes + (if (now == LikeStatus.DISLIKE) 1 else 0) - (if (ratedBefore == LikeStatus.DISLIKE) 1 else 0)
        }

    /** Likes or dislikes; the same again takes the rating back. */
    fun rate(status: LikeStatus) {
        val before = state ?: return
        val rating = if (before.like == status) LikeStatus.INDIFFERENT else status
        state = before.copy(like = rating)
        launchAction(onFailure = { state = state?.copy(like = before.like) }) { Actions.rate(item.id, rating) }
    }

    fun subscribe(subscribe: Boolean) {
        val before = state ?: return
        val channel = before.channelId ?: return
        state = before.copy(subscribed = subscribe)
        launchAction(onFailure = { state = state?.copy(subscribed = before.subscribed) }) { Actions.subscribe(channel, subscribe) }
    }
}
