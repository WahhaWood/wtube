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
        val language = YouTubeLanguage.current
        val body = request.dataToSend()
        val text = body?.toString(Charsets.UTF_8)
        // The extractor always asks in English; see YouTubeLanguage for why and what is left English.
        val translated = language != "en" && text != null && EXTRACTOR_HL in text &&
            "/youtubei/v1/" in request.url() && "/youtubei/v1/player" !in request.url()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), if (translated) text!!.replace(EXTRACTOR_HL, "\"hl\":\"$language\"").toRequestBody() else body?.toRequestBody())
            .url(request.url())
            .addHeader("User-Agent", Http.USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        if (translated) builder.header("Accept-Language", language)

        (clientOverride.get() ?: client()).newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body.string().let { if (translated) YouTubeLanguage.toEnglish(it) else it },
                response.request.url.toString(),
            ).also { observer.get()?.observe(request, it) }
        }
    }

    companion object {
        private const val EXTRACTOR_HL = "\"hl\":\"en-GB\""

        /** Set around a blocking extractor call to send its requests another way (the bypass checking a route). */
        val clientOverride = ThreadLocal<OkHttpClient?>()

        /** Set around a blocking extractor call to keep what it gets for [replay]. */
        internal val observer = ThreadLocal<EarlyStreams?>()

        /** Set around a blocking extractor call to answer it from what was kept, without the network. */
        internal val replay = ThreadLocal<EarlyStreams?>()
    }
}
