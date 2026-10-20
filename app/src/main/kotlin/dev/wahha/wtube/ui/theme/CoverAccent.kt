package dev.wahha.wtube.ui.theme

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import dev.wahha.wtube.data.Settings
import androidx.compose.runtime.collectAsState
import androidx.core.graphics.drawable.toBitmap

private const val SAMPLE = 32
private const val HUE_BUCKETS = 12
private val Cache = LruCache<String, Int>(64)
private const val NONE = 0

/**
 * Gives [content] a colour scheme seeded from [thumbnail]'s dominant hue while the setting is on; the scheme
 * eases from the previous accent. Covers without a clear hue (black and white, grey) keep the app's own seed.
 */
@Composable
fun CoverAccent(thumbnail: String?, content: @Composable () -> Unit) {
    val settings by Settings.current.collectAsState()
    val context = LocalContext.current
    var found by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(thumbnail, settings.coverAccent) {
        found = if (!settings.coverAccent || thumbnail == null) null else dominantColor(context, thumbnail)
    }
    val seed by animateColorAsState(found ?: Seed, tween(600), label = "coverAccent")
    MaterialTheme(colorScheme = wtubeColorScheme(seed, settings.pureBlack), typography = MaterialTheme.typography, content = content)
}

private suspend fun dominantColor(context: android.content.Context, url: String): Color? {
    Cache.get(url)?.let { return if (it == NONE) null else Color(it) }
    val request = ImageRequest.Builder(context).data(url).size(Size(SAMPLE, SAMPLE)).allowHardware(false).build()
    val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
    val bitmap = result.image.asDrawable(context.resources).toBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888)
    return dominantHue(bitmap).also { Cache.put(url, it ?: NONE) }?.let(::Color)
}

/**
 * The hue bucket that carries the most saturated, mid-bright pixels, averaged into one colour; null when the
 * picture has none (monochrome covers).
 */
internal fun dominantHue(bitmap: Bitmap): Int? {
    val weight = FloatArray(HUE_BUCKETS)
    val hueSum = FloatArray(HUE_BUCKETS)
    val hsv = FloatArray(3)
    for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
        AndroidColor.colorToHSV(bitmap.getPixel(x, y), hsv)
        if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue
        val bucket = (hsv[0] / 360f * HUE_BUCKETS).toInt().coerceAtMost(HUE_BUCKETS - 1)
        val w = hsv[1] * hsv[2]
        weight[bucket] += w
        hueSum[bucket] += hsv[0] * w
    }
    val best = weight.indices.maxByOrNull { weight[it] } ?: return null
    if (weight[best] < SAMPLE * SAMPLE * 0.02f) return null
    return AndroidColor.HSVToColor(floatArrayOf(hueSum[best] / weight[best], 0.6f, 0.85f))
}
