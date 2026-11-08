package dev.wahha.wtube.ui.components

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/** The list with the item of key [moved] taken out and put right above the one of key [before], or last if there is no such item. */
internal fun <T> List<T>.movedBefore(key: (T) -> Any?, moved: Any?, before: Any?): List<T> {
    val item = firstOrNull { key(it) == moved } ?: return this
    val rest = filterNot { key(it) == moved }
    val at = rest.indexOfFirst { key(it) == before }.takeIf { it >= 0 } ?: rest.size
    return rest.take(at) + item + rest.drop(at)
}

/**
 * Drag-to-reorder for a run of rows in a lazy list. Rows trade places while the finger moves, so the list
 * can scroll under it to rows that were out of sight. Nothing is told until the drop, and a cancelled drag
 * tells nothing. Meanwhile the run is frozen in [rows]: a page arriving mid-drag cannot shift a row.
 */
@Stable
class ListReorder<T> internal constructor(
    private val state: LazyListState,
    private val key: (T) -> Any,
    private val maxSpeed: Float,
    private val source: () -> List<T>,
    private val onDrop: (moved: T, before: T?) -> Unit,
) {
    private var draft by mutableStateOf<List<T>?>(null)
    private var dragged by mutableStateOf<Any?>(null)

    // Where the finger wants the row's top, in the coordinates of LazyListItemInfo.offset.
    private var wantedTop by mutableFloatStateOf(0f)

    /** The run to show: in its order for now while dragging, otherwise the list itself. */
    val rows: List<T> get() = draft ?: source()

    internal val active: Boolean get() = dragged != null

    fun isDragged(rowKey: Any): Boolean = dragged == rowKey

    // Not extensions on Modifier: they belong to this list's drag, not to a modifier chain.

    /** Goes on the root of each row of the run. The dragged row is not animated: it follows the finger. */
    @SuppressLint("ModifierFactoryExtensionFunction")
    fun row(scope: LazyItemScope, rowKey: Any): Modifier = with(scope) {
        val moving = dragged == rowKey
        Modifier.zIndex(if (moving) 1f else 0f)
            .animateItem(
                fadeInSpec = null,
                fadeOutSpec = null,
                placementSpec = if (moving) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
            )
            .graphicsLayer {
                // From the row's current place, whatever moved it: a swap or the scroll.
                translationY = if (dragged == rowKey) info()?.let { wantedTop - it.offset } ?: 0f else 0f
            }
    }

    /** Goes on the part of a row that the finger takes it by. */
    @SuppressLint("ModifierFactoryExtensionFunction")
    @Composable
    fun handle(rowKey: Any): Modifier {
        val haptic = LocalHapticFeedback.current
        return Modifier.pointerInput(rowKey) {
            try {
                detectDragGestures(
                    onDragStart = {
                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        start(rowKey)
                    },
                    onDragEnd = ::drop,
                    onDragCancel = ::cancel,
                ) { change, amount ->
                    change.consume()
                    wantedTop += amount.y
                }
            } finally {
                // The row left the composition under the finger: the gesture ends without a word.
                if (dragged == rowKey) cancel()
            }
        }
    }

    private fun info(): LazyListItemInfo? = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragged }

    private fun start(rowKey: Any) {
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == rowKey } ?: return
        draft = source()
        wantedTop = row.offset.toFloat()
        dragged = rowKey
    }

    private fun cancel() {
        dragged = null
        draft = null
    }

    private fun drop() {
        val order = draft
        val moved = dragged
        if (order != null && moved != null) tell(order, moved)
        cancel()
    }

    // The list may have changed under the drag: rows gone stay gone, rows that arrived come after the run.
    private fun tell(order: List<T>, moved: Any) {
        val now = source()
        val held = order.mapTo(HashSet(), key)
        val present = now.associateBy(key)
        val merged = order.mapNotNull { present[key(it)] } + now.filterNot { key(it) in held }
        val at = merged.indexOfFirst { key(it) == moved }
        if (at < 0) return
        val before = merged.getOrNull(at + 1)
        val was = now.indexOfFirst { key(it) == moved }
        if (now.getOrNull(was + 1)?.let(key) != before?.let(key)) onDrop(merged[at], before)
    }

    /** While a row is dragged: once a frame, when the layout of the last swap is known, swaps and scrolls. */
    internal suspend fun follow() {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            settle()
            val delta = edgeSpeed() * (now - last) / 1_000_000_000f
            if (delta != 0f) state.scrollBy(delta)
            last = now
        }
    }

    // A row trades places once the dragged row's middle has passed its middle, so rows of another height do not flip back.
    private fun settle() {
        val current = draft ?: return
        val me = info() ?: return
        val from = current.indexOfFirst { key(it) == dragged }
        if (from < 0) return
        val middle = wantedTop + me.size / 2f
        val to = state.layoutInfo.visibleItemsInfo.mapNotNull { row ->
            val index = current.indexOfFirst { key(it) == row.key }
            val rowMiddle = row.offset + row.size / 2f
            index.takeIf { it >= 0 && (it > from && middle > rowMiddle || it < from && middle < rowMiddle) }
        }.maxByOrNull { abs(it - from) } ?: return
        // Kept in place by index: on its own the list follows its first visible row wherever that row goes.
        state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
        draft = current.toMutableList().apply { add(to, removeAt(from)) }
    }

    // Pixels per second: the deeper the row is in the band along the top or bottom edge, the faster.
    private fun edgeSpeed(): Float {
        val me = info() ?: return 0f
        val band = me.size / 2f
        val end = (state.layoutInfo.viewportEndOffset - state.layoutInfo.afterContentPadding).toFloat()
        val depth = when {
            wantedTop < band -> wantedTop - band
            wantedTop + me.size > end - band -> wantedTop + me.size - (end - band)
            else -> return 0f
        }
        // The row's own place must stay on the screen, or the list would drop the row and the gesture with it.
        val leaving = if (depth > 0) me.offset < 0 else me.offset + me.size > end
        return if (leaving) 0f else (depth / band).coerceIn(-1f, 1f) * maxSpeed
    }
}

/** A [ListReorder] for the rows of [items] shown by a list with [state]; [onDrop] gets the moved row and the one now below it, null at the end. */
@Composable
fun <T> rememberListReorder(state: LazyListState, items: List<T>, key: (T) -> Any, onDrop: (moved: T, before: T?) -> Unit): ListReorder<T> {
    val currentItems by rememberUpdatedState(items)
    val currentDrop by rememberUpdatedState(onDrop)
    val maxSpeed = with(LocalDensity.current) { 1200.dp.toPx() }
    val reorder = remember(state) { ListReorder(state, key, maxSpeed, { currentItems }) { moved, before -> currentDrop(moved, before) } }
    LaunchedEffect(reorder) {
        snapshotFlow { reorder.active }.collectLatest { if (it) reorder.follow() }
    }
    return reorder
}
