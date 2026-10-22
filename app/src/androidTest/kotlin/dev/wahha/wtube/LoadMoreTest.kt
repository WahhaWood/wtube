package dev.wahha.wtube

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
}
