package dev.wahha.wtube

import androidx.compose.ui.unit.dp
import dev.wahha.wtube.ui.components.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowLayoutTest {
    private data class Case(val name: String, val width: Int, val height: Int, val columns: Int, val twoPane: Boolean, val autoFullscreen: Boolean)

    // Window sizes in dp: Pixel 9, Pixel Fold outer and inner screens, Pixel Tablet, a tablet split in half.
    private val cases = listOf(
        Case("phone upright", 411, 914, 1, twoPane = false, autoFullscreen = false),
        Case("phone on its side", 914, 411, 2, twoPane = true, autoFullscreen = true),
        Case("fold outer upright", 411, 841, 1, twoPane = false, autoFullscreen = false),
        Case("fold inner", 841, 701, 2, twoPane = true, autoFullscreen = false),
        Case("fold inner upright", 701, 841, 2, twoPane = false, autoFullscreen = false),
        Case("tablet upright", 800, 1280, 2, twoPane = false, autoFullscreen = false),
        Case("tablet on its side", 1280, 800, 3, twoPane = true, autoFullscreen = false),
        Case("half of a tablet", 640, 800, 1, twoPane = false, autoFullscreen = false),
        Case("small phone on its side", 732, 360, 2, twoPane = false, autoFullscreen = true),
        Case("narrow split", 599, 800, 1, twoPane = false, autoFullscreen = false),
    )

    @Test
    fun windowSizesGiveTheirLayout() {
        for (case in cases) {
            val layout = WindowLayout(case.width.dp, case.height.dp)
            assertEquals(case.name, case.columns, layout.columns)
            assertEquals(case.name, case.twoPane, layout.twoPane)
            assertEquals(case.name, case.autoFullscreen, layout.autoFullscreen)
        }
    }

    @Test
    fun cardsNeverGetNarrowerThanAPhoneScreenAllows() {
        // Every width, the split-screen range included: a grid never squeezes a card below the minimum,
        // and one more column would always have to.
        for (width in 300..2600) {
            val layout = WindowLayout(width.dp, 1000.dp)
            val gutter = WindowLayout.GridGutter
            if (layout.columns > 1) {
                val card = (width.dp - gutter * (layout.columns + 1)) / layout.columns
                assert(card >= WindowLayout.CardMinWidth) { "$width dp: ${layout.columns} columns of $card" }
            }
            val more = layout.columns + 1
            val squeezed = (width.dp - gutter * (more + 1)) / more
            assert(squeezed < WindowLayout.CardMinWidth) { "$width dp: room for $more columns, got ${layout.columns}" }
        }
    }
}
