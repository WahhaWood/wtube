package dev.wahha.wtube

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.ui.components.LoadMoreEffect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadMoreTest {
    @get:Rule
    val compose = createComposeRule()

    // A channel tab or sort swaps the list behind the same LazyListState: scrolling to the end must page
    // the list shown now, not the one from the first frame.
    @Test
    fun swappedListBehindTheSameStateLoadsMore() {
        val calls = mutableListOf<String>()
        var shown by mutableStateOf("first")
        val state = LazyListState()
        compose.setContent {
            val list = shown
            LazyColumn(Modifier.height(200.dp).testTag("list"), state = state) {
                items(50) { Text("$list $it", Modifier.height(40.dp)) }
            }
            LoadMoreEffect(state) { calls += list }
        }
        compose.runOnIdle { shown = "second" }
        compose.onNodeWithTag("list").performScrollToIndex(49)
        compose.runOnIdle { assertEquals(listOf("second"), calls) }
    }

    // A page that grows the list but leaves the end still inside the threshold must trigger the next load.
    @Test
    fun smallPageStillNearEndLoadsAgain() {
        var calls = 0
        var count by mutableStateOf(10)
        val state = LazyListState()
        compose.setContent {
            LazyColumn(Modifier.height(200.dp).testTag("list"), state = state) {
                items(count) { Text("item $it", Modifier.height(40.dp)) }
            }
            LoadMoreEffect(state) { calls++ }
        }
        compose.onNodeWithTag("list").performScrollToIndex(9)
        compose.runOnIdle { assertEquals(1, calls) }
        compose.runOnIdle { count = 12 }
        compose.runOnIdle { assertEquals(2, calls) }
    }

    // A screen's filter can hide a whole page: the list keeps its size, and the next page must still load.
    @Test
    fun pageHiddenWholeLoadsTheNextOne() {
        var calls = 0
        var loaded by mutableStateOf(10)
        val state = LazyListState()
        compose.setContent {
            LazyColumn(Modifier.height(200.dp).testTag("list"), state = state) {
                items(10) { Text("item $it", Modifier.height(40.dp)) }
            }
            LoadMoreEffect(state, loaded = loaded) { calls++ }
        }
        compose.onNodeWithTag("list").performScrollToIndex(9)
        compose.runOnIdle { assertEquals(1, calls) }
        compose.runOnIdle { loaded = 20 }
        compose.runOnIdle { assertEquals(2, calls) }
    }

    // In a grid the threshold is rows of cards: three columns must start the next page three times as many cells early.
    @Test
    fun gridCountsTheThresholdInRows() {
        var calls = 0
        val state = LazyGridState()
        compose.setContent {
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(200.dp).testTag("grid"), state = state) {
                items(60) { Text("item $it", Modifier.height(40.dp)) }
            }
            LoadMoreEffect(state) { calls++ }
        }
        compose.runOnIdle { assertEquals(0, calls) }
        // Row 11 at the top: the last visible cell is about 47, six rows (18 cells) from the end of 60.
        compose.onNodeWithTag("grid").performScrollToIndex(33)
        compose.runOnIdle { assertEquals(1, calls) }
    }
}
