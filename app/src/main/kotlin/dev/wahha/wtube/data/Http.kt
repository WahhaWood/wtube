package dev.wahha.wtube.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** One OkHttp client (one connection pool) for the extractor, the player and image loading. */
object Http {
    // Same desktop Firefox UA as NewPipe's DownloaderImpl: YouTube serves the web layouts the extractor parses.
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
