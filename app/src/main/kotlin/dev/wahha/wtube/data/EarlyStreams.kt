package dev.wahha.wtube.data

import java.io.IOException
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

/**
 * Answers a second extraction from what a running one already got, so a video starts before its page is in.
 * The extractor asks for the streams first and for the page's metadata after them, one request at a time, and
 * returns when all are in: more than half of the wait before a video starts. No request is added, and if the
 * second extraction fails, the first one still ends as it always did.
 */
internal class EarlyStreams(private val onStreams: (EarlyStreams) -> Unit) {
    private var visitor: Response? = null
    private var player: Response? = null

    /** Keeps the answers of the client that delivers the streams; once they are all in, [onStreams]. */
    fun observe(request: Request, response: Response) {
        if (!request.isForStreams()) return
        when {
            VISITOR in request.url() -> visitor = response
            PLAYER in request.url() -> {
                player = response
                if (visitor != null) onStreams(this)
            }
        }
    }

    /** The kept answers; an empty `next` for the page, which is not needed here. Other metadata is optional. */
    fun answer(request: Request): Response = when {
        request.isForStreams() && VISITOR in request.url() -> visitor
        request.isForStreams() && PLAYER in request.url() -> player
        NEXT in request.url() -> Response(200, "OK", emptyMap(), EMPTY_PAGE, request.url())
        else -> null
    } ?: throw IOException("Not kept for early streams: ${request.url().substringBefore('?')}")

    private fun Request.isForStreams() = dataToSend()?.let { STREAMS_CLIENT in String(it) } == true

    private companion object {
        /** See `YoutubeStreamExtractor.fetchVisionOsClient`; checked by `ExtractorSmokeTest`. */
        const val STREAMS_CLIENT = "\"clientName\":\"VISIONOS\""
        const val VISITOR = "/visitor_id"
        const val PLAYER = "/player"
        const val NEXT = "/next"

        /** The extractor takes an answer under 50 characters for a broken one. */
        const val EMPTY_PAGE = "{\"responseContext\":{\"serviceTrackingParams\":[]},\"contents\":{}}"
    }
}
