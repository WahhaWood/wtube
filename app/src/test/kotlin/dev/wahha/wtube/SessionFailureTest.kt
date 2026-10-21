package dev.wahha.wtube

import dev.wahha.wtube.data.innertube.Failure
import dev.wahha.wtube.data.innertube.failureOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which HTTP answers may end the stored session: only the ones that say the cookies are refused. */
class SessionFailureTest {
    @Test fun unauthorizedRejectsSession() = assertEquals(Failure.SessionRejected, failureOf(401, ""))

    @Test fun jsonForbiddenRejectsSession() =
        assertEquals(Failure.SessionRejected, failureOf(403, """{"error":{"status":"PERMISSION_DENIED"}}"""))

    @Test fun botCheckPageIsNotAnExpiredSession() {
        assertEquals(Failure.Antibot, failureOf(403, "<html>Our systems have detected unusual traffic from your network</html>"))
        assertEquals(Failure.Antibot, failureOf(403, "<a href=\"https://www.google.com/sorry/index\">"))
    }

    @Test fun rateLimitIsAntibot() = assertEquals(Failure.Antibot, failureOf(429, ""))

    @Test fun otherErrorsAreJustErrors() {
        assertEquals(Failure.Http, failureOf(400, """{"error":{"status":"INVALID_ARGUMENT"}}"""))
        assertEquals(Failure.Http, failureOf(500, "<html>captcha</html>"))
    }
}

class ParseJsonTest {
    @Test fun validJsonPasses() = assertEquals("x", dev.wahha.wtube.data.innertube.parseJson("/p", """{"a":"x"}""").getString("a"))

    private fun failure(body: String) = runCatching { dev.wahha.wtube.data.innertube.parseJson("/p", body) }.exceptionOrNull()

    @Test fun htmlEmptyAndGarbageAreTold() {
        assertEquals("InnerTube /p: HTML instead of JSON", failure("<html><body>hi</body></html>")?.message)
        assertEquals("InnerTube /p: empty body", failure("  ")?.message)
        assertEquals("InnerTube /p: not JSON", failure("oops")?.message)
    }

    @Test fun botCheckPageIsCaptcha() =
        org.junit.Assert.assertTrue(failure("<html>unusual traffic from your computer network</html>") is org.schabi.newpipe.extractor.exceptions.ReCaptchaException)
}
