package dev.wahha.wtube.ui.theme

import dev.wahha.wtube.data.Settings
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

// SimpMusic's brand seed; the whole dark scheme is generated from it, as SimpMusic does (TonalSpot, no AMOLED).
internal val Seed = Color(0xFF8ECAE6)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WTubeTheme(content: @Composable () -> Unit) {
    val settings by Settings.current.collectAsState()
    val colorScheme = wtubeColorScheme(Seed, settings.pureBlack)
    // SimpMusic uses MaterialExpressiveTheme, which stable androidx material3 keeps internal.
    MaterialTheme(
        colorScheme = colorScheme,
        typography = remember(colorScheme, settings.font, settings.textSize) { wtubeTypography(colorScheme, settings.font, settings.textSize) },
    ) {
        CompositionLocalProvider(
            LocalRippleConfiguration provides SoftRippleConfiguration,
            LocalContentColor provides colorScheme.onSurfaceVariant,
            content = content,
        )
    }
}

/** The dark scheme generated from [seed]; with [pureBlack] the page itself goes black, raised surfaces keep their tint. */
@Composable
internal fun wtubeColorScheme(seed: Color, pureBlack: Boolean): ColorScheme {
    val tinted = rememberDynamicColorScheme(
        seedColor = seed,
        isDark = true,
        style = PaletteStyle.TonalSpot,
    )
    return remember(tinted, pureBlack) {
        if (pureBlack) tinted.copy(background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black) else tinted
    }
}

// Material's ripple alphas read as a grey flash on a near-black page; these are ~40% of the default.
// The (color, rippleAlpha) constructor is deprecated, but it is the only public way to set alpha.
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
private val SoftRippleConfiguration = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.06f,
        focusedAlpha = 0.04f,
        hoveredAlpha = 0.03f,
        pressedAlpha = 0.04f,
    ),
)
