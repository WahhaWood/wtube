package dev.wahha.wtube.data

import org.schabi.newpipe.extractor.exceptions.PaidContentException

/**
 * Videos only a channel's members ("спонсоры") can watch. Lists mark them, [AppSettings.membersOnly] can
 * leave them out, and what plays by itself skips them: the extractor's streams are anonymous, so none of
 * them plays here, whether or not the account is a member.
 */
object MembersOnly {
    /** Marked in every list. */
    const val SHOW = 0

    /** Left out of Home, search and related videos. */
    const val HIDE_RECOMMENDED = 1

    /** Also left out of subscriptions and channel lists; the channel's own members tab still shows them. */
    const val HIDE_FEEDS = 2

    val Choices = listOf(SHOW, HIDE_RECOMMENDED, HIDE_FEEDS)

    fun inRecommendations(item: Item, filter: Int): Boolean = !(item is VideoItem && item.membersOnly && filter >= HIDE_RECOMMENDED)

    fun inFeeds(item: Item, filter: Int): Boolean = !(item is VideoItem && item.membersOnly && filter >= HIDE_FEEDS)

    /** Whether [error] is YouTube refusing a video to non-members, not a pay-per-view one. */
    fun refused(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is PaidContentException && it.message?.contains("members") == true }

    /** YouTube's automatic playlist of channel [channelId]'s (`UC…`) members-only videos; every channel with some has it. */
    fun playlistId(channelId: String): String = "UUMO" + channelId.removePrefix("UC")

    fun joinUrl(channelUrl: String): String = channelUrl.trimEnd('/') + "/join"
}
