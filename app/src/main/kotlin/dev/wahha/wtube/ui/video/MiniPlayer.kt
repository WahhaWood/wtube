package dev.wahha.wtube.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.wahha.wtube.R
import dev.wahha.wtube.player.PlayerState
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.Pause
import dev.wahha.wtube.ui.icon.PlayArrow
import dev.wahha.wtube.ui.icon.WIcons

val MiniPlayerHeight = 60.dp
internal val MiniPlayerRadius = 22.dp

// The slot at the mini player's start (its end when left-handed) that the player sheet shrinks the video into.
internal val MiniVideoStart = 8.dp
internal val MiniVideoWidth = 76.dp
internal val MiniVideoRadius = 10.dp

/**
 * The mini player's row beside the shrunk video: title, play/pause, close and a hairline of progress.
 * [leftHanded] mirrors it: the buttons at the left edge, the video at the right.
 */
@Composable
internal fun MiniPlayerContent(
    player: Player,
    state: PlayerState,
    title: String,
    author: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    // False when the video could not be opened at all: its play button would do nothing.
    canPlay: Boolean = true,
    leftHanded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        val videoSide = MiniVideoStart + MiniVideoWidth + 10.dp
        Row(
            Modifier.fillMaxSize().padding(start = if (leftHanded) 4.dp else videoSide, end = if (leftHanded) videoSide else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val text = @Composable {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val playPause = @Composable {
                if (canPlay) Box(contentAlignment = Alignment.Center) {
                    if (state.playbackState == Player.STATE_BUFFERING) {
                        CircularProgressIndicator(Modifier.size(36.dp), color = colors.primary, strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { if (state.playWhenReady) player.pause() else player.play() }) {
                        Icon(
                            if (state.playWhenReady) WIcons.Pause else WIcons.PlayArrow,
                            contentDescription = stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                            tint = colors.onSurface,
                        )
                    }
                }
            }
            val close = @Composable {
                IconButton(onClick = onClose) {
                    Icon(WIcons.Close, contentDescription = stringResource(R.string.player_close), tint = colors.onSurface)
                }
            }
            if (leftHanded) {
                close()
                playPause()
                text()
            } else {
                text()
                playPause()
                close()
            }
        }
        val progress = if (state.duration > 0) (state.position.toFloat() / state.duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth(progress).height(2.dp)
                .background(colors.primary, RoundedCornerShape(1.dp)),
        )
    }
}
