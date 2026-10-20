package dev.wahha.wtube.ui.glass

import android.os.Build
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Liquid glass ported from SimpMusic (GPL-3): LiquidGlass.kt, LiquidGlassContainer.kt, GlassLuminance.kt.

/** The lens and vibrancy shaders need RuntimeShader (API 33); below that glass draws its flat twin. */
val GlassSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Glass on or off for the whole app: the device must support it and the user's setting must allow it. */
val LocalLiquidGlass = staticCompositionLocalOf { GlassSupported }

/**
 * The layer glass surfaces refract. The composable carrying [layerBackdrop] for it must be a SIBLING
 * of the glass, never its parent: a surface inside its own source is a render-feedback loop.
 */
@Composable
fun rememberBackdrop(color: Color): LayerBackdrop =
    rememberLayerBackdrop {
        drawRect(color)
        drawContent()
    }

/** Press state for one glass surface. Observe-only, so wrapped buttons keep their own clicks. */
class GlassInteraction(private val animationScope: CoroutineScope) {
    private val pressSpec = spring(dampingRatio = 0.5f, stiffness = 300f, visibilityThreshold = 0.001f)
    private val pressAnimation = Animatable(0f, 0.001f)

    /** 0 at rest, 1 while pressed. Read it in draw/effect/layer blocks only. */
    val pressProgress: Float get() = pressAnimation.value

    /** Local-space touch point, the centre of the press glow. */
    var touchPosition by mutableStateOf(Offset.Zero)
        private set

    suspend fun detectPress(pointer: PointerInputScope) = with(pointer) {
        inspectDragGestures(
            onDragStart = { down ->
                touchPosition = down.position
                animationScope.launch { pressAnimation.animateTo(1f, pressSpec) }
            },
            onDragEnd = { animationScope.launch { pressAnimation.animateTo(0f, pressSpec) } },
            onDragCancel = { animationScope.launch { pressAnimation.animateTo(0f, pressSpec) } },
        ) { change, _ ->
            touchPosition = change.position
        }
    }
}

@Composable
fun rememberGlassInteraction(): GlassInteraction {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassInteraction(scope) }
}

/**
 * Dark liquid glass with an optional press response: the surface scales up, refraction and blur deepen
 * and a glow follows the finger. [luminance] (0..1, from [rememberGlassLuminance]) is read while drawing,
 * so the glass darkens over bright content without recomposing. [layer] records the refracted backdrop
 * for luminance sampling; only one surface per sample may record into it.
 */
fun Modifier.drawInteractiveGlass(
    backdrop: LayerBackdrop,
    layer: GraphicsLayer?,
    luminance: () -> Float,
    shape: Shape,
    interaction: GlassInteraction?,
    fallbackColor: Color,
    pressedScale: Float = 1.12f,
    // Kyant's default rim is directional (45°): fine on a pill, nearly invisible on a small circle.
    highlight: Highlight = Highlight.Default,
    // Callers pass LocalLiquidGlass.current; off draws the flat twin with the same shape and size.
    enabled: Boolean = GlassSupported,
): Modifier {
    if (!enabled || !GlassSupported) return clip(shape).background(fallbackColor)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        highlight = { highlight },
        // Kyant's default drop shadow reads as a grey smudge under the bar on a near-black page.
        shadow = null,
        effects = {
            val l = (luminance() * 2f - 1f).let { sign(it) * it * it }
            val press = interaction?.pressProgress ?: 0f
            vibrancy()
            // Neutral brightness/contrast; the darkening happens in onDrawSurface, or bright content
            // washes the glass out to white.
            colorControls(brightness = 0.05f, contrast = 1f, saturation = 1.5f)
            blur(
                (if (l > 0f) lerp(8f.dp.toPx(), 16f.dp.toPx(), l) else lerp(8f.dp.toPx(), 2f.dp.toPx(), -l)) +
                    2f.dp.toPx() * press,
            )
            // Refraction height stays below the inradius so top and bottom never meet: that meeting
            // is the dark seam across a wide pill.
            lens(size.minDimension / 4f + 2f.dp.toPx() * press, size.minDimension / 2f, false)
        },
        onDrawBackdrop = { drawBackdrop ->
            drawBackdrop()
            layer?.record { drawBackdrop() }
        },
        onDrawSurface = {
            val darken = lerp(0.12f, 0.5f, ((luminance() - 0.3f) / 0.5f).coerceIn(0f, 1f))
            drawRect(Color.Black.copy(alpha = darken))
            val press = interaction?.pressProgress ?: 0f
            if (press > 0f) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f * press), Color.Transparent),
                        center = interaction?.touchPosition ?: center,
                        radius = size.minDimension * 1.2f,
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        },
        layerBlock = if (interaction != null) {
            {
                val scale = lerp(1f, pressedScale, interaction.pressProgress)
                scaleX = scale
                scaleY = scale
            }
        } else {
            null
        },
    ).then(
        if (interaction != null) Modifier.pointerInput(interaction) { interaction.detectPress(this) } else Modifier,
    )
}

private const val SAMPLE_SIZE = 5
private const val SAMPLE_INTERVAL_MS = 2_500L
private const val LUMINANCE_CHANGE_THRESHOLD = 0.02f

/** Average luminance (clamped to 0.3..0.8) of what [source] recorded, re-sampled every 2.5 s while started. */
@Composable
fun rememberGlassLuminance(source: GraphicsLayer, enabled: Boolean = true): State<Float> {
    val sample = rememberGraphicsLayer()
    val luminance = remember { Animatable(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    LaunchedEffect(source, sample, enabled, lifecycle, density, layoutDirection) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            while (isActive) {
                val sourceSize = source.size
                // A layer has no display list until its first draw.
                if (sourceSize.width > 0 && sourceSize.height > 0) {
                    val target = try {
                        sample.record(density, layoutDirection, IntSize(SAMPLE_SIZE, SAMPLE_SIZE)) {
                            scale(
                                scaleX = SAMPLE_SIZE.toFloat() / sourceSize.width,
                                scaleY = SAMPLE_SIZE.toFloat() / sourceSize.height,
                                pivot = Offset.Zero,
                            ) { drawLayer(source) }
                        }
                        sample.toImageBitmap().readPixels(pixels)
                        val average = pixels.sumOf { color ->
                            val r = (color shr 16 and 0xFF) / 255.0
                            val g = (color shr 8 and 0xFF) / 255.0
                            val b = (color and 0xFF) / 255.0
                            0.2126 * r + 0.7152 * g + 0.0722 * b
                        } / pixels.size
                        average.toFloat().coerceIn(0.3f, 0.8f)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // Keep the last tint and retry on the next lifecycle start, not in a loop.
                        Log.e("GlassLuminance", "Unable to sample glass background", error)
                        return@repeatOnLifecycle
                    }
                    if (abs(target - luminance.targetValue) >= LUMINANCE_CHANGE_THRESHOLD) {
                        luminance.animateTo(target, tween(500))
                    }
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }
    return luminance.asState()
}

/** Observe-only drag recogniser (Kyant's catalog DragGestureInspector): it never consumes events. */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart(down)
        onDrag(down, Offset.Zero)
        val upEvent = drag(pointerId = down.id, onDrag = { onDrag(it, it.positionChange()) })
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    if (currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true) return null
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(pointerId: PointerId): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed } ?: return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}
