package dev.wahha.wtube.player

import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.Recommendations
import dev.wahha.wtube.data.VideoItem

/** Which related video "next" and autoplay pick; one rule for the service and the video page. */
object NextVideo {
    /** The related videos that may play next: not live, nothing that cannot play (members-only, not started), not [currentId], not hidden, not under [minMinutes]. */
    fun candidates(related: List<Item>, currentId: String, hidden: HiddenItems, minMinutes: Int = 0): List<VideoItem> =
        related.filterIsInstance<VideoItem>().filter {
            !it.isLive && !it.membersOnly && !it.upcoming && it.id != currentId && hidden.allows(it) && Recommendations.longEnough(it, minMinutes)
        }

    /** The first candidate not in [watched]; with [orWatched], else the first one at all. */
    fun pick(candidates: List<VideoItem>, watched: Set<String>, orWatched: Boolean): VideoItem? =
        candidates.firstOrNull { it.id !in watched } ?: candidates.firstOrNull()?.takeIf { orWatched }
}
