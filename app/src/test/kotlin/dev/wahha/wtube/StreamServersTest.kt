package dev.wahha.wtube

import dev.wahha.wtube.data.StreamServers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

class StreamServersTest {
    private val url = "https://rr12---sn-3c27snel.googlevideo.com/videoplayback?expire=1&mn=sn-3c27snel%2Csn-4g5lznld&mvi=12&fvip=2&itag=248"
    private val spare = "https://rr2---sn-4g5lznld.googlevideo.com/videoplayback?expire=1&mn=sn-3c27snel%2Csn-4g5lznld&mvi=12&fvip=2&itag=248"
    private val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", 1080))

    @Test
    fun spareServerIsTheSecondOfMnWithFvip() {
        assertEquals(spare, StreamServers.spareUrl(url))
    }

    @Test
    fun noSpareWithoutTheParameters() {
        assertNull(StreamServers.spareUrl("https://rr1---sn-a.googlevideo.com/videoplayback?itag=1&mn=sn-a"))
        assertNull(StreamServers.spareUrl("https://rr1---sn-a.googlevideo.com/videoplayback?itag=1&mn=sn-a,sn-b"))
        assertNull(StreamServers.spareUrl("https://i.ytimg.com/vi/x/mqdefault.jpg?mn=sn-a,sn-b&fvip=1"))
    }

    @Test
    fun aServerThatFailedOnTheRouteSendsTriesToTheSpareAndBack() {
        val host = "rr12---sn-3c27snel.googlevideo.com"
        val spareHost = "rr2---sn-4g5lznld.googlevideo.com"
        assertEquals(url, StreamServers.pick(url, socks, now = 1_000))
        StreamServers.onConnectFailed(host, socks, now = 1_000)
        assertEquals(spare, StreamServers.pick(url, socks, now = 2_000))
        // Another route has not failed there.
        assertEquals(url, StreamServers.pick(url, null, now = 2_000))
        StreamServers.onConnectFailed(spareHost, socks, now = 3_000)
        assertEquals(url, StreamServers.pick(url, socks, now = 4_000))
        StreamServers.onConnected(host)
        StreamServers.onConnected(spareHost)
        assertEquals(url, StreamServers.pick(url, socks, now = 5_000))
    }
}
