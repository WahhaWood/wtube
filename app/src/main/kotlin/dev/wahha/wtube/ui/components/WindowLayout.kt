package dev.wahha.wtube.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * What the window's size asks of the layout, worked out from the window alone (never "is this a tablet"),
 * so split screen and a fold opening or closing follow by themselves. A phone held upright gets exactly
 * the old single column; on its side it gets two, since one card that wide would be taller than the screen.
 */
@Immutable
data class WindowLayout(val width: Dp, val height: Dp) {
    /** Cards side by side in feeds: as many as fit at [CardMinWidth] or wider, one when even two would not. */
    val columns: Int = maxOf(1, ((width - GridGutter) / (CardMinWidth + GridGutter)).toInt())

    /** The widest a single column of text, settings or an ordered list may grow before it is centred. */
    val contentMaxWidth: Dp = ContentMaxWidth

    /** Room for the video page and its side list next to each other. */
    val twoPane: Boolean = width >= TWO_PANE_FROM

    /** A phone on its side: too low for a page under the video, so the video takes the screen. */
    val autoFullscreen: Boolean = width > height && height < AUTO_FULLSCREEN_BELOW

    val grid: Boolean get() = columns > 1

    companion object {
        val TWO_PANE_FROM = 840.dp
        val AUTO_FULLSCREEN_BELOW = 480.dp
        val CardMinWidth = 320.dp
        val ContentMaxWidth = 720.dp

        /** The gap between cards and at the window's sides, once there is more than one column. */
        val GridGutter = 16.dp
    }
}

/** The window's [WindowLayout], provided at the root from its constraints. */
val LocalWindowLayout = staticCompositionLocalOf { WindowLayout(400.dp, 800.dp) }

/**
 * A feed: one column on a phone, a grid of cards on a wider window. One code path and one [LazyGridState]
 * for both, so the scroll position survives a fold opening. Headers go in with [fullRow].
 */
@Composable
fun FeedGrid(
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: LazyGridScope.() -> Unit,
) {
    val layout = LocalWindowLayout.current
    val side = gridSide()
    val direction = LocalLayoutDirection.current
    CompositionLocalProvider(LocalInGrid provides layout.grid) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(layout.columns),
        modifier = modifier,
        state = state,
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + side,
            end = contentPadding.calculateEndPadding(direction) + side,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(side),
        content = content,
    )
    }
}

/** True inside a [FeedGrid] of more than one column: a card there is a cell, not a phone's full-width row. */
val LocalInGrid = staticCompositionLocalOf { false }

/** The margin [FeedGrid] keeps at the window's sides: none for one column, [WindowLayout.GridGutter] for a grid. */
@Composable
fun gridSide(): Dp = if (LocalWindowLayout.current.grid) WindowLayout.GridGutter else 0.dp

/** Lets a [fullRow] reach the window's edges past [FeedGrid]'s side margin [side] (a banner, a strip of chips). */
fun Modifier.bleed(side: Dp): Modifier = if (side == 0.dp) this else layout { measurable, constraints ->
    val extra = (side * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-side.roundToPx(), 0) }
}

private val FullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** A row across every column: a header, a banner, a footer, a state. */
fun LazyGridScope.fullRow(key: Any, contentType: Any? = null, content: @Composable LazyGridItemScope.() -> Unit) =
    item(key = key, span = FullSpan, contentType = contentType, content = content)

/**
 * Side padding for a full-width list of text, settings or ordered rows: keeps them within
 * [WindowLayout.contentMaxWidth], centred, while the whole window width still scrolls the list.
 */
@Composable
fun readableSide(): Dp {
    val layout = LocalWindowLayout.current
    return ((layout.width - layout.contentMaxWidth) / 2).coerceAtLeast(0.dp)
}
