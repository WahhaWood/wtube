package dev.wahha.wtube.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.wahha.wtube.R

@OptIn(ExperimentalTextApi::class)
private fun variable(font: Int, normal: Int) = FontFamily(
    Font(font, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(normal))),
    Font(font, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(font, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(font, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/**
 * The fonts Settings offers, by [dev.wahha.wtube.data.AppSettings.font]: Inter (narrow, neutral, full
 * Cyrillic), the phone's own Roboto as in the YouTube app, and Montserrat, the look of 0.1–0.7 (it
 * stands in for SimpMusic's Poppins, which has no Cyrillic; its "Normal" is Medium, as Poppins was drawn).
 */
private val Families = listOf(
    variable(R.font.inter, 400),
    FontFamily.Default,
    variable(R.font.montserrat, 500),
)

/** Text size choices relative to the scale below, by [dev.wahha.wtube.data.AppSettings.textSize]. */
val TextScales = listOf(0.92f, 1f, 1.08f)

fun wtubeTypography(colorScheme: ColorScheme, font: Int, textSize: Int): Typography {
    val family = Families.getOrElse(font) { Families.first() }
    val scale = TextScales.getOrElse(textSize) { 1f }
    val title = TextStyle(fontFamily = family, color = colorScheme.onBackground)
    val body = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, color = colorScheme.onSurfaceVariant)
    // SimpMusic's scale, a step smaller everywhere: the user found it too large.
    return Typography(
        titleSmall = title.copy(fontSize = (13 * scale).sp, fontWeight = FontWeight.SemiBold),
        titleMedium = title.copy(fontSize = (16 * scale).sp, fontWeight = FontWeight.SemiBold),
        titleLarge = title.copy(fontSize = (22 * scale).sp, fontWeight = FontWeight.Bold),
        bodySmall = body.copy(fontSize = (11.5f * scale).sp),
        bodyMedium = body.copy(fontSize = (13 * scale).sp),
        bodyLarge = body.copy(fontSize = (16 * scale).sp),
        displayLarge = body.copy(fontSize = (18 * scale).sp),
        headlineMedium = body.copy(fontSize = (19 * scale).sp, fontWeight = FontWeight.Bold),
        headlineLarge = body.copy(fontSize = (21 * scale).sp, fontWeight = FontWeight.Bold),
        labelMedium = body.copy(fontSize = (14 * scale).sp, fontWeight = FontWeight.Bold),
        labelSmall = body.copy(fontSize = (12.5f * scale).sp, fontWeight = FontWeight.SemiBold),
    )
}
