package dev.wahha.wtube

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.ui.components.ListReorder
import dev.wahha.wtube.ui.components.movedBefore
import dev.wahha.wtube.ui.components.rememberListReorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListReorderTest {
    @get:Rule
    val compose = createComposeRule()

    private val drops = mutableListOf<Pair<String, String?>>()
    private var items by mutableStateOf(listOf("a", "b", "c", "d", "e"))
    private lateinit var reorder: ListReorder<String>
    private var rowPx = 0f

    private fun show(viewport: Int = 400) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val state = rememberLazyListState()
            reorder = rememberListReorder(state, items, { it }) { moved, before ->
                drops += moved to before
                items = items.movedBefore({ it }, moved, before)
            }
            LazyColumn(Modifier.height(viewport.dp), state = state) {
                item(key = "header") { Text("header", Modifier.height(40.dp)) }
                items(reorder.rows, key = { it }) { row ->
                    Box(reorder.row(this, row).fillMaxWidth().height(40.dp)) {
                        Box(reorder.handle(row).testTag("handle $row").height(40.dp).fillMaxWidth())
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        rowPx = with(compose.density) { 40.dp.toPx() }
    }

    private fun frames(count: Int = 4) = repeat(count) { compose.mainClock.advanceTimeByFrame() }

    /** Past the slop first, then [rows] rows further, a frame per step like a real finger. */
    private fun drag(row: String, rows: Float) {
        compose.onNodeWithTag("handle $row").performTouchInput {
            down(center)
            moveBy(Offset(0f, if (rows < 0) -viewConfiguration.touchSlop - 1 else viewConfiguration.touchSlop + 1))
        }
        frames()
        moveFurther(row, rows)
    }

    private fun moveFurther(row: String, rows: Float) = repeat(8) {
        compose.onNodeWithTag("handle $row").performTouchInput { moveBy(Offset(0f, rows * rowPx / 8)) }
        frames(2)
    }

    private fun lift(row: String) {
        compose.onNodeWithTag("handle $row").performTouchInput { up() }
        frames()
    }

    @Test fun dropTellsTheRowAndTheOneNowBelowIt() {
        show()
        drag("b", 2f)
        lift("b")
        assertEquals(listOf("b" to "e"), drops)
        assertEquals(listOf("a", "c", "d", "b", "e"), items)
    }

    @Test fun droppingLastTellsThereIsNothingBelow() {
        show()
        drag("c", 5f)
        lift("c")
        assertEquals(listOf<Pair<String, String?>>("c" to null), drops)
    }

    @Test fun rowsTradePlacesBeforeTheDropAndTheSourceWaits() {
        show()
        drag("a", 2f)
        assertEquals(listOf("b", "c", "a", "d", "e"), reorder.rows)
        assertEquals(listOf("a", "b", "c", "d", "e"), items)
        lift("a")
    }

    @Test fun droppedWhereItWasTellsNothing() {
        show()
        drag("b", 0.2f)
        lift("b")
        assertEquals(emptyList<Pair<String, String?>>(), drops)
    }

    @Test fun cancelledDragTellsNothingAndRestoresTheRows() {
        show()
        drag("a", 2f)
        compose.onNodeWithTag("handle a").performTouchInput { cancel() }
        frames()
        assertEquals(emptyList<Pair<String, String?>>(), drops)
        assertEquals(listOf("a", "b", "c", "d", "e"), reorder.rows)
    }

    @Test fun pageArrivingMidDragDoesNotShiftRowsAndComesAfterTheRun() {
        show()
        drag("b", 1f)
        val during = reorder.rows
        items = items + listOf("f", "g")
        frames()
        assertEquals(during, reorder.rows)
        moveFurther("b", 4f)
        lift("b")
        // Dropped last of what was held, it now stands above the first row of the new page.
        assertEquals(listOf<Pair<String, String?>>("b" to "f"), drops)
    }

    @Test fun holdingAtTheEdgeScrollsToRowsOutOfSight() {
        items = (0 until 40).map { "r$it" }
        show(viewport = 200)
        // Down to the bottom band and held there: the list scrolls, the row keeps trading places.
        drag("r1", 3f)
        frames(120)
        val at = reorder.rows.indexOf("r1")
        assertTrue("still at $at", at > 8)
        lift("r1")
        assertEquals("r1", drops.single().first)
        assertEquals(at, items.indexOf("r1"))
    }

    @Test fun movedBeforeHandlesEveryPlace() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), list.movedBefore({ it }, "b", "a"))
        assertEquals(listOf("b", "c", "a"), list.movedBefore({ it }, "a", null))
        assertEquals(list, list.movedBefore({ it }, "x", "a"))
        assertEquals(listOf("b", "c", "a"), list.movedBefore({ it }, "a", "gone"))
    }
}
