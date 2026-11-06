package dev.wahha.wtube

import androidx.media3.common.PlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.data.innertube.InnerTubeFormatException
import dev.wahha.wtube.ui.components.errorMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import java.io.IOException

/** Under Robolectric: [PlaybackException] reads the framework's clock. */
@RunWith(AndroidJUnit4::class)
class ErrorMessageTest {
    private fun playerError(cause: Throwable?, code: Int) = PlaybackException("failed", cause, code)

    @Test
    fun aDecoderTakenBackIsNotTheExtractorsFault() {
        val reclaimed = playerError(IllegalStateException("codec"), PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED)
        assertEquals(R.string.error_playback, errorMessage(reclaimed))
    }

    @Test
    fun aPlayerErrorThroughTheNetworkIsANetworkError() {
        val network = playerError(IOException("offline"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals(R.string.error_network, errorMessage(network))
    }

    @Test
    fun onlyWhatFailedToParseBlamesTheExtractor() {
        assertEquals(R.string.error_extractor, errorMessage(ParsingException("no title")))
        assertEquals(R.string.error_extractor, errorMessage(ExtractionException("no streams")))
        assertEquals(R.string.error_extractor, errorMessage(InnerTubeFormatException("/browse", "not JSON")))
    }

    @Test
    fun extractorFailingOnTheNetworkIsANetworkError() {
        assertEquals(R.string.error_network, errorMessage(ExtractionException("no page", IOException("offline"))))
    }

    @Test
    fun anythingElseIsNotBlamedOnTheExtractor() {
        assertEquals(R.string.error_unknown, errorMessage(IllegalStateException("a bug of ours")))
    }
}
