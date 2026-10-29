package dev.wahha.wtube

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.player.isExpiredLink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Under Robolectric: the check needs a real [Uri], which the android.jar stubs do not have. */
@RunWith(AndroidJUnit4::class)
class ExpiredLinkTest {
    private fun httpError(code: Int): PlaybackException = ExoPlaybackException.createForSource(
        HttpDataSource.InvalidResponseCodeException(code, null, null, emptyMap(), DataSpec(Uri.EMPTY), ByteArray(0)),
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    )

    @Test
    fun forbiddenAndGoneAreExpiredLinks() {
        assertTrue(httpError(403).isExpiredLink())
        assertTrue(httpError(410).isExpiredLink())
    }

    @Test
    fun otherErrorsAreNot() {
        assertFalse(httpError(404).isExpiredLink())
        assertFalse(httpError(500).isExpiredLink())
        val network = ExoPlaybackException.createForSource(IOException("offline"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertFalse(network.isExpiredLink())
    }
}
