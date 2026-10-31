package dev.wahha.wtube.player

import dev.wahha.wtube.ui.components.errorDetail
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.text.TextRenderer
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.wahha.wtube.MainActivity
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.Http
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.WatchProgress
import dev.wahha.wtube.data.VideoItem
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.capLongSide
import dev.wahha.wtube.data.qualityCap
import dev.wahha.wtube.data.watchUrl
import dev.wahha.wtube.download.Downloads
import dev.wahha.wtube.dpi.Dpi
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Owns the player, so audio keeps going with the app in the background; Media3 shows the media
 * notification and lock-screen controls for the session on its own. The UI talks to it through a
 * MediaController and sends items whose media id is a YouTube video id.
 *
 * Whatever has to keep working with the app in the background lives here too: the sleep timer, autoplay
 * of the next video, and the speed and track preferences from Settings.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = MainScope()
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, SubtitleRenderersFactory(this))
            .setMediaSourceFactory(YoutubeMediaSourceFactory())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo = player
        // The original audio over dubs (and over auto-dubs in the phone's language); human captions over
        // auto-generated ones of the same language.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN)
            .setPreferredTextRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
        player.addListener(PlayerListener(player))
        player.addListener(WatchReporter(scope, player))
        player.addListener(StatsRecorder(scope, player))
        player.addListener(SegmentSkipper(this, scope, player))
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
            }
        })
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SessionPlayer(player))
            .setSessionActivity(openApp)
            .setCallback(Callback())
            .build()

        scope.launch { Settings.current.collect { applyTrackPreferences() } }
        scope.launch { Settings.current.map { it.speed }.distinctUntilChanged().collect { player.setPlaybackSpeed(it) } }
        scope.launch { SleepTimer.sleep.collectLatest { sleep -> sleep?.atMs?.let { sleepAt(it) } } }
        scope.launch { Autoplay.upcoming.collectLatest { upcoming -> upcoming?.let { playUpcoming(it) } } }
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    // Wi-Fi ↔ mobile switches move the quality cap mid-video.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // Only a real comeback resumes: capabilities also change with every bit of signal strength, and
        // retrying a dead stream on each of those could keep the phone busy all night on a weak network.
        private var offline = false

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val cameBack = online && offline
            offline = !online
            scope.launch {
                applyTrackPreferences()
                if (cameBack) resumeAfterOutage()
            }
        }

        override fun onLost(network: Network) {
            offline = true
        }
    }

    /** A video that stopped for lack of network goes on by itself once the network is back, from the same spot. */
    private fun resumeAfterOutage() {
        val player = session?.player ?: return
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) return
        val code = player.playerError?.errorCode ?: return
        if (code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ) player.prepare()
    }

    /**
     * The automatic quality limit for the current network, sized by the long side (16:9 of the cap) so it
     * caps vertical videos by their short side too; a manual pick in the player overrides it. Plus captions:
     * on or off, and the language picked last.
     */
    private fun applyTrackPreferences() {
        val player = session?.player ?: return
        val settings = Settings.current.value
        val cap = qualityCap(settings, connectivity.isActiveNetworkMetered)
        val longSide = cap?.let(::capLongSide) ?: Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(longSide, longSide)
            .setPreferredTextLanguage(settings.subtitleLanguage)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.subtitles)
            .build()
    }

    private suspend fun sleepAt(atMs: Long) {
        delay(atMs - SystemClock.elapsedRealtime())
        // Within the end screen's countdown pause() changes nothing, and the next video would start.
        Autoplay.cancel()
        session?.player?.pause()
        SleepTimer.cancel()
    }

    private suspend fun playUpcoming(upcoming: Upcoming) {
        delay(upcoming.atMs - SystemClock.elapsedRealtime())
        val player = session?.player ?: return
        // The timer's own wake-up pauses and clears the countdown.
        if (!mayPlayUpcoming(upcoming.atMs, SleepTimer.sleep.value)) return
        val item = runCatching { mediaItem(upcoming.video.id) }.getOrNull()
        Autoplay.cancel()
        if (item == null) return
        player.setMediaItem(item, resumePosition(item.mediaId))
        player.prepare()
        player.play()
        History.record(upcoming.video)
    }

    /**
     * At the end of a video: the next one in [PlayQueue] right away, else (with autoplay on) the first
     * related one not watched yet, after a countdown on the end screen.
     */
    private fun scheduleNext(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        PlayQueue.pop()?.let {
            Autoplay.schedule(it, 0)
            return
        }
        if (!Settings.current.value.autoplay) return
        scope.launch {
            val next = relatedNext(id, orWatched = false)
            // Still at the end of the same video: the user may have replayed it or opened another meanwhile.
            if (next != null && player.playbackState == Player.STATE_ENDED && player.currentMediaItem?.mediaId == id) {
                Autoplay.schedule(next, AUTOPLAY_DELAY_MS)
            }
        }
    }

    /** "Next" from the notification, lock screen or headset: the queue's first, else a related video, as the player's button. */
    private fun skipToNext(player: Player) {
        val id = player.currentMediaItem?.mediaId ?: return
        PlayQueue.pop()?.let {
            Autoplay.schedule(it, 0)
            return
        }
        scope.launch {
            val next = relatedNext(id, orWatched = true)
            if (next != null && player.currentMediaItem?.mediaId == id) Autoplay.schedule(next, 0)
        }
    }

    /** The first related video not watched yet (or, with [orWatched], the first one at all); not live, not hidden. */
    private suspend fun relatedNext(id: String, orWatched: Boolean): VideoItem? {
        val watched = History.entries.value.mapTo(HashSet()) { it.video.id }
        val hidden = Hidden.items.value
        val related = runCatching { YouTube.video(id).related }.getOrDefault(emptyList())
        return NextVideo.pick(NextVideo.candidates(related, id, hidden), watched, orWatched)
    }

    /**
     * What the session sees: the player holds one video at a time, the queue and autoplay live outside it,
     * so "next" is always offered to the system and handled by [skipToNext] (as LibreTube's forwarder does).
     */
    private inner class SessionPlayer(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build()

        override fun isCommandAvailable(command: Int): Boolean =
            command == COMMAND_SEEK_TO_NEXT || command == COMMAND_SEEK_TO_NEXT_MEDIA_ITEM || super.isCommandAvailable(command)

        override fun hasNextMediaItem(): Boolean = currentMediaItem != null

        override fun seekToNext() = skipToNext(this)

        override fun seekToNextMediaItem() = skipToNext(this)
    }

    private inner class PlayerListener(private val player: Player) : Player.Listener {
        // The video whose links were last refreshed and when, so a link that keeps failing is refreshed only once.
        private var refreshed: Pair<String, Long>? = null
        private var stall: Job? = null

        /** Stream links expire after some hours: refetch them once and go on from the same spot, without a button. */
        override fun onPlayerError(error: PlaybackException) {
            val id = player.currentMediaItem?.mediaId ?: return
            // Into the app's own event file: on some phones the shared logcat is nearly empty.
            val causes = generateSequence(error.cause) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message.orEmpty().take(120)}" }
            ErrorLog.note("[player] ${error.errorCodeName} on $id, route ${Http.route ?: "direct"}: $causes")
            if (!error.isExpiredLink()) return
            val now = SystemClock.elapsedRealtime()
            refreshed?.let { (lastId, at) -> if (lastId == id && now - at < REFRESH_AGAIN_MS) return }
            refreshed = id to now
            val position = player.currentPosition
            val playWhenReady = player.playWhenReady
            Log.i(TAG, "Stream links of $id expired, refreshing")
            scope.launch {
                YouTube.forget(id)
                val item = try {
                    mediaItem(id)
                } catch (e: Exception) {
                    Log.w(TAG, "Refreshing $id failed", e)
                    ErrorLog.note("[player] refreshing $id failed: ${errorDetail(e)}")
                    return@launch
                }
                if (player.currentMediaItem?.mediaId != id) return@launch
                player.setMediaItem(item, position)
                player.prepare()
                player.playWhenReady = playWhenReady
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            watchStall(playbackState)
            if (playbackState != Player.STATE_ENDED) {
                Autoplay.cancel()
                return
            }
            // ExoPlayer can leave playWhenReady=true at the end. A later lifecycle or network
            // callback must not turn a completed item into an unexpected replay.
            player.playWhenReady = false
            if (SleepTimer.sleep.value?.endOfVideo == true) {
                SleepTimer.cancel()
            } else {
                scheduleNext(player)
            }
        }

        /** A throttled stream trickles instead of failing, so no error ever says why; a long wait is noted too. */
        private fun watchStall(playbackState: Int) {
            stall?.cancel()
            if (playbackState != Player.STATE_BUFFERING) return
            stall = scope.launch {
                delay(STALL_NOTE_MS)
                if (!player.playWhenReady) return@launch
                val buffered = (player.bufferedPosition - player.currentPosition) / 1000
                ErrorLog.note("[player] buffering ${STALL_NOTE_MS / 1000} s on ${player.currentMediaItem?.mediaId} (${buffered} s ahead), route ${Http.route ?: "direct"}")
                player.currentMediaItem?.mediaId?.let { Dpi.onVideoStalled(it) }
            }
        }

        /**
         * "The video jumps back a couple of seconds by itself" has no known cause yet: every backward jump
         * within one video goes to the event log with its reason, who asked for it (this app's package for
         * the seek bar and double taps, another app for a headset or the system, "player" for the service
         * itself) and what the player was doing, so the shared log tells which one it was.
         */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val id = newPosition.mediaItem?.mediaId ?: return
            if (oldPosition.mediaItem?.mediaId != id) return
            val back = oldPosition.positionMs - newPosition.positionMs
            if (back < BACK_JUMP_NOTE_MS) return
            val by = session?.controllerForCurrentRequest?.packageName ?: "player"
            val ahead = (player.bufferedPosition - player.currentPosition) / 1000
            ErrorLog.note(
                "[player] jumped back %.1f s (%.1f → %.1f s) on %s: %s by %s, state %d, playing %b, speed %.2f, %d s buffered, %dp, route %s".format(
                    Locale.ROOT, back / 1000.0, oldPosition.positionMs / 1000.0, newPosition.positionMs / 1000.0, id,
                    discontinuityName(reason), by, player.playbackState, player.isPlaying, player.playbackParameters.speed, ahead,
                    exo.videoFormat?.height ?: 0, Http.route ?: "direct",
                ),
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                // A quality, dub or caption track picked by hand belongs to the previous video.
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
            } else if (SleepTimer.sleep.value?.endOfVideo == true) {
                // With repeat on the video never ends; the timer's "end of video" is the moment it starts over.
                player.pause()
                SleepTimer.cancel()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // Swiping the app away from recents closes it for good: playback stops and the notification goes, as
    // the user expects of "closed". Home or the power button keep playing in the background.
    override fun onTaskRemoved(rootIntent: Intent?) {
        session?.player?.saveProgress()
        pauseAllPlayersAndStopSelf()
        // The system kills the process right after the task goes, often before onDestroy: release the
        // session now, or its notification stays behind without an app.
        session?.run {
            player.release()
            release()
        }
        session = null
        NotificationManagerCompat.from(this).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID)
    }

    override fun onDestroy() {
        connectivity.unregisterNetworkCallback(networkCallback)
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * The item for video [id] with the metadata the notification shows; fetches the stream info into the
     * cache. A downloaded video plays from its file; a downloaded sound only when the video cannot be had.
     */
    private suspend fun mediaItem(id: String): MediaItem {
        Downloads.finished(id, audioOnly = false)?.let { return item(it.video, Uri.fromFile(File(it.file))) }
        val details = try {
            YouTube.video(id).item
        } catch (e: Exception) {
            val sound = Downloads.finished(id, audioOnly = true) ?: throw e
            return item(sound.video, Uri.fromFile(File(sound.file)))
        }
        return item(details, Uri.parse(watchUrl(details.id)))
    }

    private fun item(video: VideoItem, uri: Uri): MediaItem = MediaItem.Builder()
        .setMediaId(video.id)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(video.title)
                .setArtist(video.channelName)
                .setArtworkUri(video.thumbnail?.let(Uri::parse))
                .build(),
        )
        .build()

    /** "Stats for nerds": what is playing and how, straight from the ExoPlayer the controller cannot see. */
    private fun stats(): Bundle {
        if (!::exo.isInitialized) return Bundle.EMPTY
        val player = exo
        return Bundle().apply {
            player.videoFormat?.let { f ->
                putString(STAT_VIDEO, "${f.codecs ?: f.sampleMimeType} ${f.width}×${f.height}@${f.frameRate.toInt()}")
                putInt(STAT_VIDEO_BITRATE, f.bitrate)
            }
            player.audioFormat?.let { f ->
                putString(STAT_AUDIO, "${f.codecs ?: f.sampleMimeType} ${f.sampleRate} Hz ${f.language.orEmpty()}".trim())
                putInt(STAT_AUDIO_BITRATE, f.bitrate)
            }
            putString(STAT_DECODERS, listOfNotNull(videoDecoder, audioDecoder).joinToString(" / "))
            player.videoDecoderCounters?.let { c ->
                c.ensureUpdated()
                putInt(STAT_DROPPED, c.droppedBufferCount)
                putInt(STAT_RENDERED, c.renderedOutputBufferCount)
            }
            putLong(STAT_BUFFER_MS, (player.bufferedPosition - player.currentPosition).coerceAtLeast(0))
            putLong(STAT_BANDWIDTH, DefaultBandwidthMeter.getSingletonInstance(this@PlaybackService).bitrateEstimate)
            putString(STAT_VIDEO_ID, player.currentMediaItem?.mediaId)
        }
    }

    private inner class Callback : MediaSession.Callback {
        // The app itself, its notification, SystemUI (lock screen, quick settings) and Bluetooth hold
        // MEDIA_CONTENT_CONTROL and get everything. Any other app may only play and pause: it does not
        // learn what is playing, and cannot start videos.
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.isTrusted || controller.packageName == packageName) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(StatsCommand).build(),
                    )
                    .build()
            }
            Log.i(TAG, "Limited media controller: ${controller.packageName}")
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                .setAvailableSessionCommands(SessionCommands.EMPTY)
                .setAvailablePlayerCommands(Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).build())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> =
            if (customCommand == StatsCommand) {
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS, stats()))
            } else {
                super.onCustomCommand(session, controller, customCommand, args)
            }

        // Controllers send bare video ids; fill in the stream info (cached by the video screen already)
        // and the metadata the notification shows.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.map { mediaItem(it.mediaId) }.toMutableList()
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val REFRESH_AGAIN_MS = 10 * 60_000L
        private const val BACK_JUMP_NOTE_MS = 1_000L
        private const val STALL_NOTE_MS = 15_000L
        const val AUTOPLAY_DELAY_MS = 8_000L
        val StatsCommand = SessionCommand("dev.wahha.wtube.STATS", Bundle.EMPTY)
        const val STAT_VIDEO = "video"
        const val STAT_VIDEO_BITRATE = "video_bitrate"
        const val STAT_AUDIO = "audio"
        const val STAT_AUDIO_BITRATE = "audio_bitrate"
        const val STAT_DECODERS = "decoders"
        const val STAT_DROPPED = "dropped"
        const val STAT_RENDERED = "rendered"
        const val STAT_BUFFER_MS = "buffer"
        const val STAT_BANDWIDTH = "bandwidth"
        const val STAT_VIDEO_ID = "id"
    }
}

/**
 * Caption tracks come as single WebVTT samples (see [YoutubeMediaSourceFactory]), which the text renderer
 * decodes only in its legacy mode. Deprecated in Media3, but the lazy-loading replacement that
 * DefaultMediaSourceFactory uses is package-private; revisit when Media3 drops legacy decoding.
 */
@OptIn(UnstableApi::class)
private class SubtitleRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildTextRenderers(context: Context, output: TextOutput, outputLooper: Looper, extensionRendererMode: Int, out: ArrayList<Renderer>) {
        out.add(TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
    }
}

/** Saved position to resume [videoId] from, or [C.TIME_UNSET] to start at the beginning. */
fun resumePosition(videoId: String): Long {
    if (!Settings.current.value.resumePlayback) return C.TIME_UNSET
    return WatchProgress.resumeMs(videoId).let { if (it > PlayerConnection.RESUME_MIN_MS) it else C.TIME_UNSET }
}

/** googlevideo answers 403 (sometimes 410) once a stream link has expired or no longer matches the session. */
fun PlaybackException.isExpiredLink(): Boolean = generateSequence<Throwable>(this) { it.cause }
    .any { it is HttpDataSource.InvalidResponseCodeException && (it.responseCode == 403 || it.responseCode == 410) }

private fun discontinuityName(reason: Int): String = when (reason) {
    Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "auto transition"
    Player.DISCONTINUITY_REASON_SEEK -> "seek"
    Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "seek adjustment"
    Player.DISCONTINUITY_REASON_SKIP -> "skip"
    Player.DISCONTINUITY_REASON_REMOVE -> "item replaced"
    Player.DISCONTINUITY_REASON_INTERNAL -> "internal"
    Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "silence skip"
    else -> "reason $reason"
}
