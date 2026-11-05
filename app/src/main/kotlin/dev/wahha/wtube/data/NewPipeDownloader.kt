package dev.wahha.wtube.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/** NewPipeExtractor's HTTP hook on OkHttp, as in NewPipe's DownloaderImpl (minus cookies until login). */
class NewPipeDownloader(private val client: () -> OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        replay.get()?.let { return it.answer(request) }
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            .url(request.url())
            .addHeader("User-Agent", Http.USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        (clientOverride.get() ?: client()).newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string(),
                response.request.url.toString(),
            ).also { observer.get()?.observe(request, it) }
        }
    }

    companion object {
        /** Set around a blocking extractor call to send its requests another way (the bypass checking a route). */
        val clientOverride = ThreadLocal<OkHttpClient?>()

        /** Set around a blocking extractor call to keep what it gets for [replay]. */
        internal val observer = ThreadLocal<EarlyStreams?>()

        /** Set around a blocking extractor call to answer it from what was kept, without the network. */
        internal val replay = ThreadLocal<EarlyStreams?>()
    }
}
