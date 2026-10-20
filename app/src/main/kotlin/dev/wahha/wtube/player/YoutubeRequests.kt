package dev.wahha.wtube.player

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import dev.wahha.wtube.data.Http
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.util.concurrent.atomic.AtomicLong

/**
 * Rewrites player requests to googlevideo the way YouTube's own clients make them, after NewPipe's
 * YoutubeHttpDataSource: the byte range goes into a `range` URL parameter instead of the Range header
 * (header ranges get throttled), every request carries an `rn` counter, the method is POST with a
 * two-byte body, and the user agent matches the client the URL was issued to.
 */
@OptIn(UnstableApi::class)
object YoutubeRequests : ResolvingDataSource.Resolver {
    private val requestNumber = AtomicLong()
    private val PostBody = byteArrayOf(0x78, 0)

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.path?.startsWith("/videoplayback") != true) return dataSpec
        val original = dataSpec.uri.toString()
        val headers = HashMap(dataSpec.httpRequestHeaders) + clientHeaders(original)
        return dataSpec.buildUpon()
            .setUri(Uri.parse(videoplaybackUrl(original, dataSpec.position, dataSpec.length, requestNumber.getAndIncrement())))
            // The range now travels in the URL; the response body is exactly the requested bytes.
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(PostBody)
            .setHttpRequestHeaders(headers)
            .build()
    }

    /** The user agent of the client [url] was issued to, plus the web origin for web URLs. */
    fun clientHeaders(url: String): Map<String, String> = buildMap {
        put("User-Agent", if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else Http.USER_AGENT)
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
        }
    }

    /** Bytes [from] until [to] (exclusive) of a googlevideo [url], asked for the way the player asks. */
    fun rangeRequest(url: String, from: Long, to: Long): Request {
        val builder = Request.Builder()
            .url(videoplaybackUrl(url, from, to - from, requestNumber.getAndIncrement()))
            .post(PostBody.toRequestBody())
        clientHeaders(url).forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }
}

/** [url] with `rn` (unless already there, as in DASH segment URLs) and, for partial reads, `range`. */
fun videoplaybackUrl(url: String, position: Long, length: Long, requestNumber: Long): String = buildString {
    append(url)
    if ("&rn=" !in url) append("&rn=").append(requestNumber)
    if (position != 0L || length != -1L) {
        append("&range=").append(position).append('-')
        if (length != -1L) append(position + length - 1)
    }
}
