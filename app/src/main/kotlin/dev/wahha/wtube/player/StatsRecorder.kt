package dev.wahha.wtube.player

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.StatsEntry
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Counts how long each video really plays (wall-clock time, so speed does not stretch it) into [WatchStats].
 * The running count is handed over on pause, on a change of video and every 30 s, so a killed
 * process loses seconds, not the session.
 */
internal class StatsRecorder(private val scope: CoroutineScope, private val player: Player) : Player.Listener {
    private var videoId: String? = null
    private var startedAt = 0L
    private var ticker: Job? = null

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) begin() else flush()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // The player already points at the new item; the old one is counted under the id kept at begin().
        flush()
        if (player.isPlaying) begin()
    }

    private fun begin() {
        ticker?.cancel()
        commit()
        mark()
        ticker = scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                commit()
                mark()
            }
        }
    }

    private fun mark() {
        videoId = player.currentMediaItem?.mediaId
        startedAt = SystemClock.elapsedRealtime()
    }

    private fun flush() {
        ticker?.cancel()
        commit()
    }

    /** Hands the seconds played since [mark] to [WatchStats]. */
    private fun commit() {
        val id = videoId ?: return
        videoId = null
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        if (seconds <= 0 || !Settings.current.value.saveHistory) return
        val video = History.entryOf(id)?.video
        val info = if (video == null) YouTube.cachedStreamInfo(id) else null
        val title = video?.title ?: info?.name ?: return
        WatchStats.add(
            StatsEntry(
                day = WatchStats.dayOf(System.currentTimeMillis()),
                videoId = id,
                title = title,
                channel = video?.channelName ?: info?.uploaderName,
                channelUrl = video?.channelUrl ?: info?.uploaderUrl,
                seconds = seconds,
            ),
        )
    }

    private companion object {
        const val FLUSH_MS = 30_000L
    }
}
