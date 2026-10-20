package dev.wahha.wtube

import dev.wahha.wtube.data.ErrorLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ErrorLogTest {
    @Test
    fun cutsStreamLinks() {
        val line = "E ExoPlayer: Response code: 403 for https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&ip=1.2.3.4&sig=x retrying"
        assertEquals("E ExoPlayer: Response code: 403 for [googlevideo link] retrying", ErrorLog.redact(line))
    }

    @Test
    fun cutsCookiesAndAuthHeader() {
        val text = "Cookie: SID=abc; __Secure-3PSID=def; PREF=f6=40000000\nAuthorization: SAPISIDHASH 123_abcdef"
        val redacted = ErrorLog.redact(text)
        assertEquals("Cookie: SID=[cut]; __Secure-3PSID=[cut]; PREF=f6=40000000\nAuthorization: SAPISIDHASH [cut]", redacted)
        assertFalse(redacted.contains("abc"))
    }

    @Test
    fun leavesOrdinaryLinesAlone() {
        val line = "W PagedList: Page failed: IOException: timeout (youtube.com/watch?v=dQw4w9WgXcQ)"
        assertEquals(line, ErrorLog.redact(line))
    }
}
