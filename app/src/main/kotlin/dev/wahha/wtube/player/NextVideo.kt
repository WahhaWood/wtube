package dev.wahha.wtube.player

import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.VideoItem

/** Which related video "next" and autoplay pick; one rule for the service and the video page. */
object NextVideo {
    /** The related videos that may play next: not live, not [currentId], not hidden. */
    fun candidates(related: List<Item>, currentId: String, hidden: HiddenItems): List<VideoItem> =
        related.filterIsInstance<VideoItem>().filter { !it.isLive && it.id != currentId && hidden.allows(it) }

    /** The first candidate not in [watched]; with [orWatched], else the first one at all. */
    fun pick(candidates: List<VideoItem>, watched: Set<String>, orWatched: Boolean): VideoItem? =
        candidates.firstOrNull { it.id !in watched } ?: candidates.firstOrNull()?.takeIf { orWatched }
}
