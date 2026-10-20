package dev.wahha.wtube.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

// Ported from SimpMusic's LiquidGlassTabBar.android.kt (GPL-3), itself adapted from Kyant's LiquidBottomTabs.
// The folding into a circle (for the mini player) is left out until there is a mini player.

val CapsuleShape = RoundedCornerShape(percent = 50)
private val TabWidth = 96.dp
val BarHeight = 64.dp
private val BlobHeight = 56.dp

// Room between the capsule's rounded end and the pill on the first/last tab.
private val BarInset = 6.dp

class GlassTab(val label: String, val icon: ImageVector)

/**
 * iOS 26-style liquid tab bar, three layers bottom to top: the dark glass capsule, a frosted blob that
 * slides to the selected tab (squashing with drag velocity), and the icons with labels, kept crisp on top.
 * [selectedTab] may be -1 when the current screen belongs to no tab; the blob then stays where it was.
 */
@Composable
fun LiquidGlassTabBar(
    tabs: List<GlassTab>,
    selectedTab: Int,
    backdrop: LayerBackdrop,
    layer: GraphicsLayer,
    luminance: State<Float>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    availableWidth: Dp = Dp.Unspecified,
) {
    val density = LocalDensity.current
    val tabsCount = tabs.size
    // Every tab is the same width: the blob sits at `index * tabWidth` and the drag divides by it.
    val tabWidth = tabWidthFor(tabsCount, availableWidth)
    val fullWidth = tabWidth * tabsCount + BarInset * 2
    val tabWidthPx = with(density) { tabWidth.toPx() }
    // The remembered drag lambdas would otherwise keep the width from the first layout.
    val currentTabWidthPx by rememberUpdatedState(tabWidthPx)
    val animationScope = rememberCoroutineScope()
    val barInteraction = rememberGlassInteraction()
    val colors = MaterialTheme.colorScheme
    val glass = LocalLiquidGlass.current

    var currentIndex by remember { mutableIntStateOf(selectedTab.coerceAtLeast(0)) }
    // [0] = a real drag happened (vs a tap), so the blob does not snap back and steal the tap.
    val draggedFlag = remember { booleanArrayOf(false) }

    val dampedDrag = remember(animationScope, tabsCount) {
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = selectedTab.coerceAtLeast(0).toFloat(),
            valueRange = 0f..(tabsCount - 1).toFloat(),
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            // On press the blob inflates past the bar's height, so it bulges out of the capsule.
            pressedScale = 76f / 56f,
            onDragStarted = { draggedFlag[0] = false },
            onDragStopped = {
                if (draggedFlag[0]) {
                    val target = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = target
                    // Snap even when the rounded target is unchanged, or the blob stops mid-slide.
                    animateToValue(target.toFloat())
                }
            },
            onDrag = { _, dragAmount ->
                if (dragAmount.x != 0f) draggedFlag[0] = true
                updateValue((targetValue + dragAmount.x / currentTabWidthPx).coerceIn(0f, (tabsCount - 1).toFloat()))
            },
        )
    }

    // Follow selection changes from outside (back stack).
    LaunchedEffect(selectedTab) {
        if (selectedTab >= 0 && currentIndex != selectedTab) currentIndex = selectedTab
    }
    LaunchedEffect(dampedDrag) {
        snapshotFlow { currentIndex }
            .drop(1)
            .collectLatest { index ->
                dampedDrag.animateToValue(index.toFloat())
                // Taps navigate from onClick already; only a drag-snap navigates here.
                if (draggedFlag[0]) onTabSelected(index)
            }
    }

    Box(
        modifier = modifier
            .height(BarHeight)
            .width(fullWidth)
            // The common ancestor sees the touch on the Initial pass before the children; the glass
            // underneath only reads barInteraction to draw the scale and glow.
            .pointerInput(barInteraction) { barInteraction.detectPress(this) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .matchParentSize()
                .drawInteractiveGlass(
                    backdrop = backdrop,
                    layer = layer,
                    luminance = { luminance.value },
                    shape = CapsuleShape,
                    interaction = barInteraction,
                    fallbackColor = colors.surfaceContainerHigh,
                    enabled = glass,
                ),
        )

        val blob = Modifier.graphicsLayer {
            translationX = dampedDrag.value * tabWidthPx + BarInset.toPx()
        }
        Box(
            if (glass && GlassSupported) {
                blob.drawBackdrop(
                    backdrop = backdrop,
                    shape = { CapsuleShape },
                    effects = {
                        val l = (luminance.value * 2f - 1f).let { sign(it) * it * it }
                        val progress = dampedDrag.pressProgress
                        vibrancy()
                        colorControls(brightness = 0.05f, contrast = 1f, saturation = 1.5f)
                        // Stronger than the capsule's blur, so the active pill reads as frosted.
                        blur(
                            (if (l > 0f) lerp(8f.dp.toPx(), 16f.dp.toPx(), l) else lerp(8f.dp.toPx(), 2f.dp.toPx(), -l)) +
                                20f.dp.toPx(),
                        )
                        lens(10f.dp.toPx() * progress, 14f.dp.toPx() * progress, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = 0.6f) },
                    shadow = null,
                    innerShadow = {
                        val progress = dampedDrag.pressProgress
                        InnerShadow(radius = 8f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDrag.scaleX
                        scaleY = dampedDrag.scaleY
                        val velocity = dampedDrag.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val lumNorm = ((luminance.value - 0.3f) / 0.5f).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = lerp(0.22f, 0.55f, lumNorm)))
                    },
                )
            } else {
                blob.background(colors.onSurface.copy(alpha = 0.12f), CapsuleShape)
            }
                .width(tabWidth)
                .height(BlobHeight),
        )

        // Tabs tile exactly [BarInset..+tabWidth..], the origin the blob uses.
        Row(
            Modifier
                .matchParentSize()
                .padding(horizontal = BarInset)
                .then(dampedDrag.modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { position, tab ->
                LiquidGlassTab(tab = tab, selected = currentIndex == position, width = tabWidth) {
                    if (position != currentIndex) {
                        // Navigate on tap directly: a drag-stop on the same Row can reset currentIndex
                        // before a snapshotFlow emits, which would drop the tap.
                        currentIndex = position
                    }
                    onTabSelected(position)
                }
            }
        }
    }
}

/** The capsule's laid-out width, for siblings that line up with it (the mini player). */
fun liquidTabBarWidth(tabsCount: Int, availableWidth: Dp): Dp = tabWidthFor(tabsCount, availableWidth) * tabsCount + BarInset * 2

private fun tabWidthFor(tabsCount: Int, availableWidth: Dp): Dp =
    if (availableWidth.isSpecified && availableWidth > 0.dp) {
        ((availableWidth - BarInset * 2) / tabsCount).coerceAtMost(TabWidth)
    } else {
        TabWidth
    }

@Composable
private fun LiquidGlassTab(tab: GlassTab, selected: Boolean, width: Dp, onClick: () -> Unit) {
    val color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clip(CapsuleShape)
            .clickable(interactionSource = null, indication = null, role = Role.Tab, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = null, tint = color)
        Text(tab.label, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
    }
}

/**
 * Spring-damped 1-D drag across the tab range: the continuous [value], the drag [velocity] for
 * squash and stretch, [pressProgress] (rest to lifted glass) and decoupled [scaleX]/[scaleY] springs.
 */
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos {}
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange.start, valueRange.endInclusive)
        animationScope.launch {
            valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange.start, valueRange.endInclusive)
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}
