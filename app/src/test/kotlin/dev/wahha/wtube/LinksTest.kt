package dev.wahha.wtube

import dev.wahha.wtube.data.LinkTarget
import dev.wahha.wtube.data.YouTube
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    @Test
    fun accountListsHaveShortIds() {
        assertEquals("WL", YouTube.playlistIdOf("https://www.youtube.com/playlist?list=WL"))
        assertEquals("LL", YouTube.playlistIdOf("https://www.youtube.com/playlist?list=LL"))
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", YouTube.playlistIdOf("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
        assertNull(YouTube.playlistIdOf("https://example.com/playlist?list=WL"))
    }

    @Test
    fun watchLinkInsidePlaylistIsTheVideo() {
        assertEquals("dQw4w9WgXcQ", YouTube.videoIdOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&index=2"))
        assertEquals("dQw4w9WgXcQ", YouTube.videoIdOf("https://www.youtube.com/watch?list=WL&v=dQw4w9WgXcQ"))
        assertNull(YouTube.videoIdOf("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"))
    }

    @Test
    fun pastedLinksOpenTheirTarget() {
        assertEquals(LinkTarget.Video("dQw4w9WgXcQ"), YouTube.linkTarget("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals(LinkTarget.Video("dQw4w9WgXcQ"), YouTube.linkTarget(" youtube.com/watch?v=dQw4w9WgXcQ&list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI "))
        assertEquals(
            LinkTarget.Playlist("https://www.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
            YouTube.linkTarget("https://m.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"),
        )
        assertEquals(LinkTarget.Channel("https://www.youtube.com/@LofiGirl"), YouTube.linkTarget("https://www.youtube.com/@LofiGirl"))
        assertEquals(LinkTarget.Live("https://www.youtube.com/@LofiGirl/live"), YouTube.linkTarget("https://www.youtube.com/@LofiGirl/live?si=abc"))
        assertEquals(LinkTarget.Live("https://m.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow/live"), YouTube.linkTarget("https://m.youtube.com/channel/UCSJ4gkVC6NrvII8umztf0Ow/live"))
        assertEquals(LinkTarget.Video("jfKfPfyJRdk"), YouTube.linkTarget("https://www.youtube.com/live/jfKfPfyJRdk?si=abc"))
        assertNull(YouTube.linkTarget("lofi hip hop"))
        assertNull(YouTube.linkTarget("https://example.com/watch?v=dQw4w9WgXcQ"))
    }
}
