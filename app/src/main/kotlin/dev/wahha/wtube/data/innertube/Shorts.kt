package dev.wahha.wtube.data.innertube

import com.grack.nanojson.JsonObject
import dev.wahha.wtube.data.PageOf
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.account.Session
import org.schabi.newpipe.extractor.Page

/** One short in the feed: its id and the vertical first frame to show until the video plays. */
data class ShortItem(val id: String, val thumbnail: String?)

/**
 * The Shorts feed as the web client's Shorts button loads it, the account's own when signed in. It starts
 * "seedless": YouTube picks the first short and hands a sequence token, which then gives the rest in
 * batches, each with the token of the next. Only the ids come from here; a short's page and streams are
 * an ordinary video's.
 */
object Shorts {
    // What the guide's Shorts entry sends; see `reelWatchEndpoint` in the `guide` answer.
    private const val SEEDLESS_PARAMS = "CA8%3D"
    private const val SEEDLESS_INPUT = "REEL_WATCH_INPUT_TYPE_SEEDLESS"

    /** The first short and the token of the rest when [page] is null, else the batch [page] points to. */
    suspend fun page(page: Page?, session: Session? = Account.active): PageOf<ShortItem> =
        if (page == null) {
            first(InnerTube.post(session, "reel/reel_item_watch") {
                value("params", SEEDLESS_PARAMS)
                value("inputType", SEEDLESS_INPUT)
                value("disablePlayerResponse", true)
            })
        } else {
            sequence(InnerTube.post(session, "reel/reel_watch_sequence") { value("sequenceParams", page.id) })
        }

    internal fun first(response: JsonObject): PageOf<ShortItem> {
        val endpoint = response.getObject("replacementEndpoint").getObject("reelWatchEndpoint")
        val items = listOfNotNull(item(endpoint))
        if (items.isEmpty()) throw InnerTubeFormatException("reel_item_watch", "no short")
        return PageOf(items, response.getString("sequenceContinuation")?.takeIf { it.isNotEmpty() }?.let(Feeds::cursor))
    }

    internal fun sequence(response: JsonObject): PageOf<ShortItem> {
        val items = response.getArray("entries").mapNotNull { entry ->
            item((entry as? JsonObject)?.getObject("command")?.getObject("reelWatchEndpoint"))
        }
        val token = FeedParser.findFirst(response["continuationEndpoint"], "continuationCommand")?.getString("token")
        return PageOf(items, token?.takeIf { it.isNotEmpty() }?.let(Feeds::cursor))
    }

    private fun item(endpoint: JsonObject?): ShortItem? {
        val id = endpoint?.getString("videoId")?.takeIf { it.length == VIDEO_ID_LENGTH } ?: return null
        val thumbnail = endpoint.getObject("thumbnail").getArray("thumbnails")
            .filterIsInstance<JsonObject>()
            .maxByOrNull { it.getInt("height") }
            ?.getString("url")
        return ShortItem(id, thumbnail)
    }

    private const val VIDEO_ID_LENGTH = 11
}
