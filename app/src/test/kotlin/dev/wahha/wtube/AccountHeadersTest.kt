package dev.wahha.wtube

import dev.wahha.wtube.data.innertube.AccountHeaders
import dev.wahha.wtube.data.innertube.mayCarryAccount
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** The account's cookies go to YouTube and Google only: the stats URL comes from a page, and a redirect can lead anywhere. */
class AccountHeadersTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun youTubeAndGoogleMayCarryTheAccount() {
        listOf(
            "https://s.youtube.com/api/stats/playback?ns=yt",
            "https://www.youtube.com/api/stats/watchtime",
            "https://youtube.com/",
            "https://accounts.google.com/",
            "https://S.YouTube.COM./x",
        ).forEach { assertTrue(it, mayCarryAccount(it.toHttpUrl())) }
    }

    @Test
    fun lookalikesAndPlainHttpMayNot() {
        listOf(
            "https://youtube.com.evil.example/api/stats",
            "https://evilyoutube.com/",
            "https://notgoogle.com/",
            "https://youtube.com@evil.example/",
            "https://evil.example/youtube.com",
            "https://rr1---sn-abc.googlevideo.com/videoplayback",
            "http://s.youtube.com/api/stats/playback",
        ).forEach { assertFalse(it, mayCarryAccount(it.toHttpUrl())) }
    }

    /** All names lead to [server]; TLS is left out, so here only the host decides. */
    private fun client() = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
        })
        .addNetworkInterceptor(AccountHeaders { url -> url.host.endsWith(".youtube.com") })
        .build()

    private fun signed(url: String) = Request.Builder().url(url)
        .header("Cookie", "SAPISID=secret")
        .header("Authorization", "SAPISIDHASH x")
        .header("X-Goog-AuthUser", "0")
        .header("X-Goog-PageId", "1")
        .header("User-Agent", "test")
        .build()

    @Test
    fun aRedirectToAnotherHostLeavesTheAccountBehind() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.example:${server.port}/next"))
        server.enqueue(MockResponse().setResponseCode(204))
        client().newCall(signed("http://s.youtube.com:${server.port}/api/stats/playback")).execute().close()

        val first = server.takeRequest()
        assertEquals("SAPISID=secret", first.getHeader("Cookie"))
        assertEquals("0", first.getHeader("X-Goog-AuthUser"))

        val second = server.takeRequest()
        assertEquals("/next", second.path)
        listOf("Cookie", "Authorization", "X-Goog-AuthUser", "X-Goog-PageId").forEach { assertNull(it, second.getHeader(it)) }
        assertEquals("test", second.getHeader("User-Agent"))
    }

    @Test
    fun aRedirectWithinYouTubeKeepsTheAccount() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://www.youtube.com:${server.port}/next"))
        server.enqueue(MockResponse().setResponseCode(204))
        client().newCall(signed("http://s.youtube.com:${server.port}/api/stats/playback")).execute().close()

        server.takeRequest()
        assertEquals("SAPISID=secret", server.takeRequest().getHeader("Cookie"))
    }
}
