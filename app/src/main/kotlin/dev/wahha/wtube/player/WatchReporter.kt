package dev.wahha.wtube.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.account.Account
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.data.innertube.Tracking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tells YouTube what is watched here, so the account's history and recommendations include it. Does what
 * the web player does (and yt-dlp's `--mark-watched`): a playback ping when a video starts, then watchtime
 * pings with the ranges played — every [INTERVAL_MS] while playing, and on pause, seek and leaving the video.
 * Only with an account and the setting on; failures are dropped, playback never waits for them.
 */
internal class WatchReporter(private val scope: CoroutineScope, private val player: Player) : Player.Listener {
    private class Report(val videoId: String, val cpn: String) {
        var tracking: Tracking? = null
        /** Start of the range played since the last watchtime ping, in seconds. */
        var from = 0.0
    }

    private var report: Report? = null
    private var ticker: Job? = null

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) start() else pause()
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val report = report ?: return
        if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
        if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) return
        watchtime(report, oldPosition.positionMs / 1000.0, playing = player.isPlaying)
        report.from = newPosition.positionMs / 1000.0
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The position already belongs to the new item: the old one keeps what it reported up to its last tick or pause.
        report = null
        ticker?.cancel()
        if (player.isPlaying) start()
    }

    private fun start() {
        val videoId = player.currentMediaItem?.mediaId ?: return
        if (!Settings.current.value.reportWatching || Account.active == null) return
        val report = report?.takeIf { it.videoId == videoId } ?: Report(videoId, cpn()).also { fresh ->
            report = fresh
            fresh.from = positionSeconds()
            scope.launch {
                val tracking = runQuietly { Actions.tracking(videoId) } ?: return@launch
                fresh.tracking = tracking
                runQuietly { Actions.ping(fresh.url(tracking.playbackUrl, "cmt" to seconds(positionSeconds()))) }
            }
        }
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(INTERVAL_MS)
                watchtime(report, positionSeconds(), playing = true)
                report.from = positionSeconds()
            }
        }
    }

    private fun pause() {
        ticker?.cancel()
        val report = report ?: return
        // Paused or stopped; at the end of a video the position stays at its duration.
        watchtime(report, positionSeconds(), playing = false)
        report.from = positionSeconds()
    }

    private fun watchtime(report: Report, to: Double, playing: Boolean) {
        val tracking = report.tracking ?: return
        if (to <= report.from) return
        val url = report.url(
            tracking.watchtimeUrl,
            "st" to seconds(report.from),
            "et" to seconds(to),
            "cmt" to seconds(to),
            "state" to if (playing) "playing" else "paused",
        )
        scope.launch { runQuietly { Actions.ping(url) } }
    }

    private fun positionSeconds() = player.currentPosition / 1000.0

    private fun Report.url(base: String, vararg params: Pair<String, String>): String =
        base.toHttpUrl().newBuilder().apply {
            setQueryParameter("ver", "2")
            setQueryParameter("cpn", cpn)
            // Without it YouTube files the view under Shorts.
            setQueryParameter("el", "detailpage")
            params.forEach { (name, value) -> setQueryParameter(name, value) }
        }.build().toString()

    private suspend fun <T> runQuietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Only the class: messages may quote URLs that identify the session.
        Log.w("WatchReporter", "Report failed: ${e.javaClass.simpleName}")
        null
    }

    companion object {
        const val INTERVAL_MS = 40_000L
        private const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /** The client playback nonce that ties one view's pings together, as the web player makes it. */
        fun cpn(): String = (1..16).map { CPN_ALPHABET.random() }.joinToString("")

        fun seconds(value: Double): String = String.format(Locale.US, "%.3f", value)
    }
}
