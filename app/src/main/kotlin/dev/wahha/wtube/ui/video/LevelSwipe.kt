package dev.wahha.wtube.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.ui.icon.BrightnessMedium
import dev.wahha.wtube.ui.icon.VolumeUp
import dev.wahha.wtube.ui.icon.WIcons
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a vertical swipe at the side of a fullscreen video sets. */
internal enum class LevelKind { Brightness, Volume }

/**
 * The level a fullscreen swipe starting at [x], [y] on a [width] × [height] video sets, or null when it is
 * not one: the middle and the [edge] strips at the top and bottom (shade, navigation, seek bar) stay with
 * the sheet, as do swipes turned off in Settings.
 */
internal fun levelSwipeAt(x: Float, y: Float, width: Float, height: Float, edge: Float, settings: AppSettings): LevelKind? {
    if (y < edge || y > height - edge) return null
    val zone = width * Settings.GestureZones[settings.gestureZone.coerceIn(Settings.GestureZones.indices)]
    val left = when {
        x < zone -> true
        x > width - zone -> false
        else -> return null
    }
    val kind = if (left != settings.gestureSwapSides) LevelKind.Brightness else LevelKind.Volume
    val enabled = if (kind == LevelKind.Brightness) settings.gestureBrightness else settings.gestureVolume
    return kind.takeIf { enabled }
}

/** Clearly up or down: a diagonal drag is a seek or nothing, never a level change by accident. */
internal fun isLevelDrag(dx: Float, dy: Float): Boolean = abs(dy) > abs(dx) * 1.5f

/** The level after a finger moved by [dy] pixels (down is positive) on a video [height] pixels tall. */
internal fun levelAfter(level: Float, dy: Float, height: Float, settings: AppSettings, min: Float = 0f): Float {
    val span = Settings.GestureSpans[settings.gestureSensitivity.coerceIn(Settings.GestureSpans.indices)]
    return (level - dy / (height * span)).coerceIn(min, 1f)
}

/** A level bar for volume and brightness swipes, [level] in 0..1, with its percent. */
@Composable
internal fun LevelBadge(kind: LevelKind, level: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.clip(RoundedCornerShape(50)).background(colors.scrim.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (kind == LevelKind.Brightness) WIcons.BrightnessMedium else WIcons.VolumeUp, contentDescription = null, tint = colors.onSurface, modifier = Modifier.size(20.dp))
        Box(Modifier.width(120.dp).height(4.dp).clip(RoundedCornerShape(50)).background(colors.onSurface.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(level.coerceIn(0f, 1f)).height(4.dp).background(colors.primary))
        }
        Text("${(level.coerceIn(0f, 1f) * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = colors.onSurface, modifier = Modifier.width(40.dp))
    }
}
