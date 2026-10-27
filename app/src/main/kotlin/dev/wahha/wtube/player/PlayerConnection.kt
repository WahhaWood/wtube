package dev.wahha.wtube.player

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.YouTube
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await

/** The UI's handle on [PlaybackService]; [player] is null until the controller connects. */
class PlayerConnection(context: Context) {
    private val future = MediaController.Builder(
        context,
        SessionToken(context, ComponentName(context, PlaybackService::class.java)),
    ).buildAsync()

    var player by mutableStateOf<MediaController?>(null)
        private set

    init {
        future.addListener({ player = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
    }

    /** A video brought back after the process was killed: [play] loads it from its saved position but stays paused. */
    var restoring: String? = null

    /** Starts [videoId] from its saved position, unless it is already the current item. */
    fun play(videoId: String) {
        val player = player ?: return
        Autoplay.cancel()
        val paused = restoring == videoId
        restoring = null
        if (player.currentMediaItem?.mediaId == videoId && player.playerError == null) {
            if (paused) return
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
            return
        }
        player.setMediaItem(MediaItem.Builder().setMediaId(videoId).build(), resumePosition(videoId))
        player.prepare()
        if (!paused) player.play()
    }

    /** After a playback error: fetch fresh stream URLs and continue from the same spot. */
    fun retry() {
        val player = player ?: return
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        YouTube.forget(id)
        player.setMediaItem(MediaItem.Builder().setMediaId(id).build(), position)
        player.prepare()
        player.play()
    }

    fun stop() {
        Autoplay.cancel()
        SleepTimer.cancel()
        player?.run {
            saveProgress()
            stop()
            clearMediaItems()
        }
    }

    /** [PlaybackService]'s stats for nerds, keyed by its `STAT_` constants; empty when unavailable. */
    suspend fun stats(): Bundle {
        val player = player ?: return Bundle.EMPTY
        return runCatching { player.sendCustomCommand(PlaybackService.StatsCommand, Bundle.EMPTY).await().extras }.getOrDefault(Bundle.EMPTY)
    }

    fun release() = MediaController.releaseFuture(future)

    companion object {
        const val RESUME_MIN_MS = 10_000L
    }
}

/** Remembers how far the current video got; near the end counts as finished, so it starts over next time. */
fun Player.saveProgress() {
    val id = currentMediaItem?.mediaId ?: return
    // A place in a live stream's moving window means nothing the next time.
    if (isCurrentMediaItemLive) return
    val duration = duration
    val position = currentPosition
    if (duration == C.TIME_UNSET || duration <= 0) return
    val finished = position > duration - 15_000
    History.savePosition(id, if (finished) 0 else position, finished)
}

val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection> { error("No PlayerConnection") }

/** The one [PlayerState] of the app shell; null until the controller connects. */
val LocalPlayerState = compositionLocalOf<PlayerState?> { null }

/** Snapshot-state mirror of a [Player], refreshed on every player event plus a position tick. */
@Stable
class PlayerState(private val player: Player) : Player.Listener {
    var isPlaying by mutableStateOf(false)
        private set
    var playWhenReady by mutableStateOf(false)
        private set
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
        private set
    var mediaId by mutableStateOf<String?>(null)
        private set
    var metadata by mutableStateOf(MediaMetadata.EMPTY)
        private set
    var error by mutableStateOf<PlaybackException?>(null)
        private set
    var tracks by mutableStateOf(Tracks.EMPTY)
        private set
    var speed by mutableFloatStateOf(1f)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var buffered by mutableLongStateOf(0L)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var parameters by mutableStateOf(player.trackSelectionParameters)
        private set
    var cues by mutableStateOf<List<Cue>>(emptyList())
        private set
    var videoSize by mutableStateOf(VideoSize.UNKNOWN)
        private set

    /** A live stream: [duration] is then its DVR window and [position] the place in it. */
    var live by mutableStateOf(false)
        private set

    init {
        sync()
    }

    override fun onEvents(player: Player, events: Player.Events) {
        val wasPlaying = isPlaying
        sync()
        if (wasPlaying && !isPlaying) player.saveProgress()
    }

    fun sync() {
        isPlaying = player.isPlaying
        playWhenReady = player.playWhenReady
        playbackState = player.playbackState
        mediaId = player.currentMediaItem?.mediaId
        metadata = player.mediaMetadata
        error = player.playerError
        tracks = player.currentTracks
        speed = player.playbackParameters.speed
        repeatMode = player.repeatMode
        parameters = player.trackSelectionParameters
        cues = player.currentCues.cues
        videoSize = player.videoSize
        live = player.isCurrentMediaItemLive
        tick()
    }

    fun tick() {
        duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
        position = player.currentPosition.coerceAtLeast(0)
        buffered = player.bufferedPosition.coerceAtLeast(0)
    }
}

@Composable
fun rememberPlayerState(player: Player): PlayerState {
    val state = remember(player) { PlayerState(player) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        player.addListener(state)
        state.sync()
        onDispose { player.removeListener(state) }
    }
    LaunchedEffect(player, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var ticks = 0
            while (true) {
                state.tick()
                // Every ~10 s of playback, persist the position for resuming later.
                if (state.isPlaying && ++ticks % 20 == 0) player.saveProgress()
                delay(500)
            }
        }
    }
    return state
}
