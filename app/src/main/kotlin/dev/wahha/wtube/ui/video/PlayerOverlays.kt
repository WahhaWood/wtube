package dev.wahha.wtube.ui.video

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.text.Cue
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import dev.wahha.wtube.R
import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.formatClock
import dev.wahha.wtube.player.PlaybackService
import dev.wahha.wtube.player.PlayerConnection
import dev.wahha.wtube.player.Upcoming
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.Forward10
import dev.wahha.wtube.ui.icon.Forward30
import dev.wahha.wtube.ui.icon.Forward5
import dev.wahha.wtube.ui.icon.Replay
import dev.wahha.wtube.ui.icon.Replay10
import dev.wahha.wtube.ui.icon.Replay30
import dev.wahha.wtube.ui.icon.Replay5
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.delay
import org.schabi.newpipe.extractor.stream.Frameset

/**
 * Captions drawn by us rather than by Media3's view-based SubtitleView: bottom centred, sized to the player
 * (so fullscreen gets bigger text), with the size and backing from Settings. [raised] lifts them above the
 * controls while those are shown.
 */
@Composable
internal fun BoxScope.SubtitleOverlay(cues: List<Cue>, settings: AppSettings, raised: Boolean) {
    val text = cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n")
    if (text.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.matchParentSize().padding(horizontal = 24.dp), contentAlignment = Alignment.BottomCenter) {
        val density = LocalDensity.current
        val scale = Settings.SubtitleScales[settings.subtitleSize]
        val size = with(density) { (maxHeight * 0.06f * scale).coerceIn(10.dp, 48.dp).toSp() }
        val style = TextStyle(
            color = colors.onSurface,
            fontSize = size,
            lineHeight = size * 1.25f,
            textAlign = TextAlign.Center,
            shadow = if (settings.subtitleBackground) null else Shadow(colors.scrim, blurRadius = 6f),
        )
        Text(
            text,
            style = style,
            modifier = Modifier
                .padding(bottom = if (raised) maxHeight * 0.22f else maxHeight * 0.05f)
                .then(
                    if (settings.subtitleBackground) {
                        Modifier.clip(RoundedCornerShape(4.dp)).background(colors.scrim.copy(alpha = 0.65f)).padding(horizontal = 6.dp, vertical = 2.dp)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * The storyboard frame at [positionMs]: YouTube's preview sheets are grids of small frames, so this loads
 * the sheet through Coil (cached) and draws just the one cell. Uses the biggest frames up to 320 px wide.
 */
@Composable
internal fun StoryboardFrame(storyboard: List<Frameset>, positionMs: Long, modifier: Modifier = Modifier) {
    val frames = storyboard.lastOrNull { it.frameWidth <= 320 } ?: storyboard.firstOrNull() ?: return
    val bounds = frames.getFrameBoundsAt(positionMs)
    val url = frames.urls.getOrNull(bounds[0]) ?: return
    val context = LocalContext.current
    val sheet by produceState<ImageBitmap?>(null, url) {
        val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(url).build())
        value = (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
    }
    val image = sheet ?: return
    Canvas(modifier.aspectRatio(frames.frameWidth.toFloat() / frames.frameHeight).clip(RoundedCornerShape(8.dp))) {
        drawImage(
            image,
            srcOffset = IntOffset(bounds[1], bounds[2]),
            srcSize = IntSize(bounds[3] - bounds[1], bounds[4] - bounds[2]),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

/** "Stats for nerds", refreshed every second from the service while shown. */
@Composable
internal fun StatsOverlay(connection: PlayerConnection, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val stats by produceState(Bundle.EMPTY) {
        while (true) {
            value = connection.stats()
            delay(1_000)
        }
    }
    val colors = MaterialTheme.colorScheme
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp, color = colors.onSurface)
    val context = LocalContext.current
    val kbps = { bits: Number -> context.getString(R.string.player_stats_kbps, (bits.toLong() / 1000).toInt()) }
    val rows = listOfNotNull(
        "id" to stats.getString(PlaybackService.STAT_VIDEO_ID),
        stringResource(R.string.player_stats_video) to stats.getString(PlaybackService.STAT_VIDEO)?.let {
            "$it, ${kbps(stats.getInt(PlaybackService.STAT_VIDEO_BITRATE))}"
        },
        stringResource(R.string.player_stats_audio) to stats.getString(PlaybackService.STAT_AUDIO)?.let {
            "$it, ${kbps(stats.getInt(PlaybackService.STAT_AUDIO_BITRATE))}"
        },
        stringResource(R.string.player_stats_decoders) to stats.getString(PlaybackService.STAT_DECODERS),
        stringResource(R.string.player_stats_frames) to stringResource(
            R.string.player_stats_frames_value,
            stats.getInt(PlaybackService.STAT_RENDERED),
            stats.getInt(PlaybackService.STAT_DROPPED),
        ),
        stringResource(R.string.player_stats_buffer) to "%.1f s".format(stats.getLong(PlaybackService.STAT_BUFFER_MS) / 1000f),
        stringResource(R.string.player_stats_bandwidth) to kbps(stats.getLong(PlaybackService.STAT_BANDWIDTH)),
    )
    Row(modifier.clip(RoundedCornerShape(8.dp)).background(colors.scrim.copy(alpha = 0.7f)).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            rows.forEach { (label, value) -> Text("$label: ${value.orEmpty()}", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
            Icon(WIcons.Close, contentDescription = stringResource(R.string.player_close), tint = colors.onSurface, modifier = Modifier.size(16.dp))
        }
    }
}

/** The end screen while autoplay counts down to the next video: what comes, with "watch now" and "cancel". */
@Composable
internal fun UpNextOverlay(upcoming: Upcoming, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(upcoming) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((upcoming.atMs - now + 999) / 1000).coerceAtLeast(0).toInt()
    Box(modifier.background(colors.scrim.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
        Row(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                upcoming.video.thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.player_up_next_in, seconds), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                Text(
                    upcoming.video.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.player_up_next_cancel)) }
                    FilledTonalButton(onClick = onPlay) { Text(stringResource(R.string.player_up_next_play)) }
                }
            }
        }
    }
}

/** A pill in the middle of the video: the 2x while held, zoom mode. */
@Composable
internal fun GestureBadge(icon: ImageVector?, text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).background(colors.scrim.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
    }
}

/**
 * The seek icon for a step of [seconds]: Material Symbols have 5, 10 and 30; other steps get the bare arrow
 * with the number drawn inside, mirrored for forward.
 */
@Composable
internal fun SeekIcon(forward: Boolean, seconds: Int, size: Dp, modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    val icon = when (seconds) {
        5 -> if (forward) WIcons.Forward5 else WIcons.Replay5
        10 -> if (forward) WIcons.Forward10 else WIcons.Replay10
        30 -> if (forward) WIcons.Forward30 else WIcons.Replay30
        else -> null
    }
    if (icon != null) {
        Icon(icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
        return
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            WIcons.Replay,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize().graphicsLayer { if (forward) scaleX = -1f },
        )
        val density = LocalDensity.current
        Text(
            seconds.toString(),
            style = TextStyle(color = tint, fontSize = with(density) { (size * 0.3f).toSp() }),
            modifier = Modifier.padding(top = size * 0.08f),
        )
    }
}

/** The time under the finger with the storyboard frame above it, centred over the seek position. */
@Composable
internal fun SeekPreview(storyboard: List<Frameset>, positionMs: Long, chapter: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (chapter != null) {
            Text(
                chapter,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(160.dp),
            )
        }
        StoryboardFrame(storyboard, positionMs, Modifier.width(160.dp))
        Text(
            formatClock(positionMs),
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.scrim.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}
