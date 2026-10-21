package dev.wahha.wtube

import dev.wahha.wtube.data.ErrorKind
import dev.wahha.wtube.data.ErrorSource
import dev.wahha.wtube.data.account.SessionExpiredException
import dev.wahha.wtube.data.errorKind
import dev.wahha.wtube.data.errorSource
import dev.wahha.wtube.data.errorTag
import dev.wahha.wtube.data.innertube.InnerTubeFormatException
import dev.wahha.wtube.data.innertube.InnerTubeHttpException
import dev.wahha.wtube.ui.components.errorDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

class DiagnosticsTest {
    @Test fun innerTubeFailuresAreTold() {
        assertEquals("[innertube/auth]", errorTag(SessionExpiredException()))
        assertEquals("[innertube/http]", errorTag(InnerTubeHttpException(400, "/next")))
        assertEquals("[innertube/parse]", errorTag(InnerTubeFormatException("/browse", "not JSON")))
    }

    @Test fun extractorFailuresAreTold() {
        assertEquals("[extractor/parse]", errorTag(ParsingException("no title")))
        assertEquals("[extractor/antibot]", errorTag(ReCaptchaException("429", "https://example.com")))
    }

    @Test fun wrappedCausesAreFound() {
        val e = ExtractionException("wrapped", IOException("reset"))
        assertEquals(ErrorKind.Network, errorKind(e))
    }

    @Test fun unknownIsOurOwn() {
        val e = IllegalStateException("bug")
        assertEquals(ErrorKind.Other, errorKind(e))
        assertEquals(ErrorSource.App, errorSource(e))
    }

    @Test fun detailLeadsWithTheTagAndKeepsNoSecrets() {
        val detail = errorDetail(InnerTubeHttpException(403, "/browse", "PERMISSION_DENIED"))
        assertEquals("[innertube/http] InnerTubeHttpException: InnerTube /browse: HTTP 403 (PERMISSION_DENIED)", detail)
        assertFalse(detail.contains("SAPISID"))
    }
}
