package dev.wahha.wtube.data

import org.schabi.newpipe.extractor.stream.ContentAvailability
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException

/** Everything that works without an account, through NewPipeExtractor. Shorts never leave this object. */
object YouTube {
    private val service get() = ServiceList.YouTube

    /** Channel tabs we show, in this order; Shorts is left out on purpose. */
    val ChannelTabKinds = listOf(ChannelTabs.VIDEOS, ChannelTabs.LIVESTREAMS, ChannelTabs.PLAYLISTS)

    /**
     * The extractor pins `hl=en-GB` and parses dates and counts with English patterns whatever is set here;
     * [NewPipeDownloader] asks YouTube in the interface language instead and turns those phrases back into
     * English ([YouTubeLanguage]). The app formats dates and counts itself from the parsed values.
     * [country] picks regional content (trending, search).
     */
    fun init(country: String) {
        val region = country.takeIf { it.length == 2 } ?: "US"
        NewPipe.init(NewPipeDownloader { Http.client }, Localization("en", region), ContentCountry(region))
    }

    suspend fun kiosk(id: String, page: Page? = null): ItemsPage = io {
        val url = service.kioskList.getListLinkHandlerFactoryByType(id).fromId(id).url
        if (page == null) {
            KioskInfo.getInfo(service, url).let { ItemsPage(it.relatedItems.toItems(), it.nextPage.valid()) }
        } else {
            KioskInfo.getMoreItems(service, url, page).let { ItemsPage(it.items.toItems(), it.nextPage.valid()) }
        }
    }

    /**
     * Home feed without an account: videos YouTube lists as related to what was watched recently.
     * [seeds] are watched video ids, newest first; each page takes the next [SEEDS_PER_PAGE] of them and
     * interleaves their related lists, so fresh watches lead and no single video takes over the feed.
     * Already watched videos are left out. The cursor is a Page whose id is the seed offset.
     */
    suspend fun recommendations(seeds: List<String>, watched: Set<String>, page: Page? = null): ItemsPage {
        val offset = page?.id?.toIntOrNull() ?: 0
        val batch = seeds.drop(offset).take(SEEDS_PER_PAGE)
        val results = coroutineScope {
            batch.map { id -> async { runCatching { video(id).related } } }.awaitAll()
        }
        // Deleted videos are skipped, but when nothing at all answered that is no network, not an empty feed.
        if (results.all { it.isFailure }) {
            results.firstNotNullOfOrNull { r -> r.exceptionOrNull()?.takeUnless { it is ContentNotAvailableException } }?.let { throw it }
        }
        val related = results.map { it.getOrDefault(emptyList()) }
        val videos = interleave(related).filterIsInstance<VideoItem>().filter { it.id !in watched }
        val next = offset + batch.size
        return ItemsPage(videos, if (next < seeds.size) Page(RECOMMENDATIONS_CURSOR, next.toString()) else null)
    }

    /** [filter] is one of `YoutubeSearchQueryHandlerFactory`'s ALL / VIDEOS / CHANNELS / PLAYLISTS. */
    suspend fun search(query: String, filter: String = YoutubeSearchQueryHandlerFactory.ALL, page: Page? = null): ItemsPage = io {
        val handler = service.searchQHFactory.fromQuery(query, listOf(filter), "")
        if (page == null) {
            SearchInfo.getInfo(service, handler).let { ItemsPage(it.relatedItems.toItems(liveWithoutDuration = true), it.nextPage.valid()) }
        } else {
            SearchInfo.getMoreItems(service, handler, page).let { ItemsPage(it.items.toItems(liveWithoutDuration = true), it.nextPage.valid()) }
        }
    }

    suspend fun suggestions(query: String): List<String> = io { service.suggestionExtractor.suggestionList(query) }

    /** [complete] is false for streams that came ahead of the page: the player can use them, nothing else can. */
    private class CachedInfo(val at: Long, val info: StreamInfo, val complete: Boolean)

    private val streamCache = object : LinkedHashMap<String, CachedInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedInfo>) = size > 20
    }

    /** An extraction under way (guarded by [streamCache]); [waiting] hear when its streams come ahead of the page. */
    private class Fetch {
        val waiting = ArrayList<() -> Unit>()
        lateinit var info: Deferred<StreamInfo>
    }

    private val fetches = HashMap<String, Fetch>()

    // Not the asker's: the service and the screen wait on one fetch, and it fills the cache if one of them leaves.
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The full stream info, shared by the video screen, the player and downloads so one watch costs one
     * fetch, also when they ask at the same time. Stream URLs expire after a few hours, so cached entries
     * live for [STREAM_TTL_MS]. [onStreams] says when [cachedStreamInfo] can already serve the player, ahead
     * of the rest, if it can.
     */
    suspend fun streamInfo(id: String, onStreams: (() -> Unit)? = null): StreamInfo {
        var streamsIn = false
        val fetch = synchronized(streamCache) {
            val cached = streamCache[id]?.takeIf { System.currentTimeMillis() - it.at < STREAM_TTL_MS }
            if (cached?.complete == true) return cached.info
            streamsIn = cached != null
            fetches.getOrPut(id) { startFetch(id, early = onStreams != null) }.also { fetch ->
                if (onStreams != null && !streamsIn) fetch.waiting += onStreams
            }
        }
        if (streamsIn) onStreams?.invoke()
        return fetch.info.await()
    }

    /** Called under the [streamCache] lock. */
    private fun startFetch(id: String, early: Boolean): Fetch {
        Log.d("YouTube", "Fetching $id")
        val fetch = Fetch()
        fetch.info = fetchScope.async {
            try {
                val scope = this
                NewPipeDownloader.observer.set(if (early) EarlyStreams { kept -> scope.launch { keepEarly(id, kept, fetch) } } else null)
                val info = try {
                    StreamInfo.getInfo(service, watchUrl(id))
                } finally {
                    NewPipeDownloader.observer.remove()
                }
                synchronized(streamCache) {
                    if (fetches[id] === fetch) streamCache[id] = CachedInfo(System.currentTimeMillis(), info, complete = true)
                }
                info
            } finally {
                synchronized(streamCache) { if (fetches[id] === fetch) fetches.remove(id) }
            }
        }
        return fetch
    }

    /** The same extraction answered from [kept]: the streams without the page, for the player; then whoever waits for them. */
    private fun keepEarly(id: String, kept: EarlyStreams, fetch: Fetch) {
        NewPipeDownloader.replay.set(kept)
        val info = try {
            StreamInfo.getInfo(service, watchUrl(id)).takeIf { it.videoOnlyStreams.isNotEmpty() || it.hlsUrl.isNotEmpty() }
        } catch (e: Exception) {
            ErrorLog.note("[player] no early streams for $id: ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
            null
        } finally {
            NewPipeDownloader.replay.remove()
        }
        if (info == null) return
        val waiting = synchronized(streamCache) {
            if (fetches[id] !== fetch || streamCache[id]?.complete == true) return
            streamCache[id] = CachedInfo(System.currentTimeMillis(), info, complete = false)
            fetch.waiting.toList()
        }
        waiting.forEach { it() }
    }

    /** Drops [id] from the cache and any fetch under way, so the next fetch gets fresh stream URLs (they expire after a few hours). */
    fun forget(id: String) {
        synchronized(streamCache) {
            streamCache.remove(id)
            fetches.remove(id)
        }
    }

    /** Gets [id] into the cache ahead of its turn; nothing is added to the fetch that would be made anyway. */
    fun prefetch(id: String) {
        fetchScope.launch { runCatching { streamInfo(id) {} } }
    }

    /** Video [id] as soon as the player can start it: from streams that came ahead of its page, or from the page. */
    suspend fun playableItem(id: String): VideoItem {
        val ready = CompletableDeferred<VideoItem>()
        fetchScope.launch {
            try {
                ready.complete(streamInfo(id) { earlyItem(id)?.let(ready::complete) }.toVideoItem(id))
            } catch (e: Exception) {
                ready.completeExceptionally(e)
            }
        }
        return ready.await()
    }

    /** Last fetched info for [id], without network; the player's media source factory runs synchronously. */
    fun cachedStreamInfo(id: String): StreamInfo? = synchronized(streamCache) { streamCache[id]?.info }

    /** Video [id] from streams that came ahead of its page, for the notification: only the WEB client has thumbnails. */
    private fun earlyItem(id: String): VideoItem? = synchronized(streamCache) { streamCache[id]?.takeUnless { it.complete }?.info }
        ?.toVideoItem(id)?.let { it.copy(thumbnail = it.thumbnail ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg") }

    /** The page of video [id]; [onStreams] says when it can play already, ahead of the page, if it can. */
    suspend fun video(id: String, onStreams: (() -> Unit)? = null): VideoDetails {
        val info = streamInfo(id, onStreams)
        return VideoDetails(
            item = info.toVideoItem(id),
            description = info.description?.content.orEmpty(),
            likes = info.likeCount,
            subscribers = info.uploaderSubscriberCount,
            uploadedAt = info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
            related = info.relatedItems.toItems(),
            chapters = info.streamSegments.map { Chapter(it.title, it.startTimeSeconds, it.previewUrl) },
            storyboard = info.previewFrames.orEmpty().sortedBy { it.frameWidth },
            captions = info.subtitles.distinctBy { it.languageTag to it.isAutoGenerated }.map { CaptionTrack(it.content, it.languageTag, it.isAutoGenerated) },
        )
    }

    /** Comments of video [id]; the first page also says whether comments are turned off. */
    suspend fun comments(id: String, page: Page? = null): PageOf<Comment> = io {
        if (page == null) {
            val info = CommentsInfo.getInfo(service, watchUrl(id))
            PageOf(info.relatedItems.map { it.toComment() }, info.nextPage.valid())
        } else {
            CommentsInfo.getMoreItems(service, watchUrl(id), page).let { PageOf(it.items.map { c -> c.toComment() }, it.nextPage.valid()) }
        }
    }

    private fun CommentsInfoItem.toComment() = Comment(
        id = commentId,
        author = uploaderName.orEmpty(),
        authorUrl = uploaderUrl,
        avatar = uploaderAvatars.pick(AVATAR_PX),
        html = commentText.content.orEmpty(),
        likes = likeCount,
        postedAt = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
        posted = textualUploadDate,
        replies = replyCount,
        pinned = isPinned,
        hearted = isHeartedByUploader,
        repliesPage = replies.valid(),
    )

    suspend fun channel(url: String): ChannelDetails = io {
        val info = ChannelInfo.getInfo(service, url)
        val tabs = info.tabs.mapNotNull { handler ->
            handler.contentFilters.firstOrNull()?.takeIf { it in ChannelTabKinds }?.let { ChannelTab(it, handler) }
        }.sortedBy { ChannelTabKinds.indexOf(it.kind) }
        ChannelDetails(
            url = url,
            id = info.id,
            name = info.name,
            avatar = info.avatars.pick(AVATAR_PX),
            banner = info.banners.pick(BANNER_PX),
            subscribers = info.subscriberCount,
            description = info.description.orEmpty(),
            verified = info.isVerified,
            tabs = tabs,
        )
    }

    suspend fun channelTab(tab: ChannelTab, page: Page? = null): ItemsPage = io {
        if (page == null) {
            ChannelTabInfo.getInfo(service, tab.handler).let { ItemsPage(it.relatedItems.toItems(), it.nextPage.valid()) }
        } else {
            ChannelTabInfo.getMoreItems(service, tab.handler, page).let { ItemsPage(it.items.toItems(), it.nextPage.valid()) }
        }
    }

    suspend fun playlist(url: String): PlaylistDetails = io {
        val info = PlaylistInfo.getInfo(service, url)
        PlaylistDetails(
            url = url,
            name = info.name,
            thumbnail = info.thumbnails.pick(THUMBNAIL_PX),
            uploader = info.uploaderName,
            count = info.streamCount,
            firstPage = ItemsPage(info.relatedItems.toItems(), info.nextPage.valid()),
        )
    }

    suspend fun playlistMore(url: String, page: Page): ItemsPage = io {
        PlaylistInfo.getMoreItems(service, url, page).let { ItemsPage(it.items.toItems(), it.nextPage.valid()) }
    }

    /** Where a pasted YouTube link leads: a video (also one inside a playlist), a playlist or a channel. */
    fun linkTarget(text: String): LinkTarget? {
        val url = text.trim().takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("youtu") || it.startsWith("www.") || it.startsWith("m.youtube") }
            ?.let { if (it.startsWith("http")) it else "https://$it" }
            ?: return null
        videoIdOf(url)?.let { return LinkTarget.Video(it) }
        playlistIdOf(url)?.let { return LinkTarget.Playlist("https://www.youtube.com/playlist?list=$it") }
        if (ChannelLive.containsMatchIn(url)) return LinkTarget.Live(url.substringBefore('?'))
        if (runCatching { service.channelLHFactory.acceptUrl(url) }.getOrDefault(false)) return LinkTarget.Channel(url)
        return null
    }

    /**
     * The video id of a YouTube link (watch, youtu.be, shorts, embed), or null when it is not one. The
     * extractor takes a watch link with `list=` for a playlist link, so that parameter is dropped first.
     */
    fun videoIdOf(url: String): String? = runCatching { service.streamLHFactory.getId(url.replace(ListQueryPart, "")) }.getOrNull()

    /** The stream a channel's live page shows now, read from the page's canonical link; null when it is not live. */
    suspend fun liveVideoId(url: String): String? = io {
        val request = Request.Builder().url(url).header("Cookie", "SOCS=CAE=").header("Accept-Language", "en").get().build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            CanonicalWatch.find(response.body.string())?.groupValues?.get(1)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun Page?.valid(): Page? = this?.takeIf { Page.isValid(it) }

    /**
     * Our rows for extractor items, Shorts dropped; also used for the account's InnerTube feeds.
     * [liveWithoutDuration]: search results mark streams that are live now only by the missing length
     * (no LIVE badge, a "N months ago" start date), so a video without one that is not scheduled is live.
     */
    internal fun List<InfoItem>.toItems(liveWithoutDuration: Boolean = false): List<Item> = mapNotNull { it.toItem(liveWithoutDuration) }

    /**
     * The playlist id of a playlist link, or null. The extractor wants ids of 10+ characters, so the
     * account's own lists (`WL` Watch later, `LL` Liked) are read from the `list` parameter directly.
     */
    fun playlistIdOf(url: String): String? = runCatching { service.playlistLHFactory.getId(url) }.getOrNull()
        ?: ListParam.find(url)?.groupValues?.get(1)?.takeIf { YoutubeHost.containsMatchIn(url) }

    private val ListParam = Regex("[?&]list=([A-Za-z0-9_-]+)")
    private val ListQueryPart = Regex("(?<=[?&])list=[^&#]*&?")
    private val ChannelLive = Regex("^https?://(www\\.|m\\.)?youtube\\.com/(@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+)/live/?([?#]|$)")
    private val CanonicalWatch = Regex("<link rel=\"canonical\" href=\"https://www\\.youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})")
    private val YoutubeHost = Regex("^https?://(www\\.|m\\.)?youtube\\.com/")

    private fun InfoItem.toItem(liveWithoutDuration: Boolean = false): Item? = when (this) {
        is StreamInfoItem -> if (isShort(this)) null else videoIdOf(url)?.let { id ->
            VideoItem(
                id = id,
                title = name,
                thumbnail = thumbnails.pick(THUMBNAIL_PX),
                channelName = uploaderName,
                channelUrl = uploaderUrl,
                channelAvatar = uploaderAvatars.pick(AVATAR_PX),
                duration = duration.coerceAtLeast(0),
                views = viewCount,
                uploaded = textualUploadDate,
                isLive = streamType.isLive() || liveWithoutDuration && duration < 0 && streamType == StreamType.VIDEO_STREAM &&
                    uploadDate?.offsetDateTime()?.isAfter(java.time.OffsetDateTime.now()) != true,
                uploadedAt = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
                membersOnly = contentAvailability == ContentAvailability.MEMBERSHIP,
                upcoming = contentAvailability == ContentAvailability.UPCOMING || uploadDate?.offsetDateTime()?.isAfter(java.time.OffsetDateTime.now()) == true,
            )
        }
        // A row YouTube sent without its title is not worth showing.
        is ChannelInfoItem -> if (name == null) null else ChannelItem(url, name, thumbnails.pick(AVATAR_PX), subscriberCount, isVerified)
        is PlaylistInfoItem -> if (name == null) null else PlaylistItem(url, name, thumbnails.pick(THUMBNAIL_PX), uploaderName, streamCount)
        else -> null
    }

    private fun StreamInfo.toVideoItem(id: String) = VideoItem(
        id = id,
        title = name,
        thumbnail = thumbnails.pick(THUMBNAIL_PX),
        channelName = uploaderName,
        channelUrl = uploaderUrl,
        channelAvatar = uploaderAvatars.pick(AVATAR_PX),
        duration = duration.coerceAtLeast(0),
        views = viewCount,
        uploaded = textualUploadDate,
        isLive = streamType.isLive(),
        uploadedAt = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
    )

    private const val SEEDS_PER_PAGE = 5
    private const val RECOMMENDATIONS_CURSOR = "wtube:recommendations"
    private const val STREAM_TTL_MS = 60 * 60 * 1000L
    private const val THUMBNAIL_PX = 480
    private const val AVATAR_PX = 88
    private const val BANNER_PX = 1060
}

/** Round-robin merge: the first item of every list, then the second of every list, and so on. */
fun <T> interleave(lists: List<List<T>>): List<T> = buildList {
    val longest = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until longest) lists.forEach { list -> list.getOrNull(i)?.let(::add) }
}

fun StreamType.isLive() = this == StreamType.LIVE_STREAM || this == StreamType.AUDIO_LIVE_STREAM

/** Shorts are dropped everywhere: the extractor's flag, plus the URL form for lists that lack the flag. */
fun isShort(item: StreamInfoItem): Boolean = item.isShortFormContent || "/shorts/" in item.url

/** The smallest image at least [minWidth] px wide, else the largest; unknown sizes count as large enough. */
fun List<Image>.pick(minWidth: Int): String? {
    if (isEmpty()) return null
    val sized = sortedBy { if (it.width > 0) it.width else Int.MAX_VALUE }
    return (sized.firstOrNull { it.width <= 0 || it.width >= minWidth } ?: sized.last()).url
}
