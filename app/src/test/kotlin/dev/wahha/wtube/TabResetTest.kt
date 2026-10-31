package dev.wahha.wtube

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.ui.components.LocalTabState
import dev.wahha.wtube.ui.components.TAB_RESET
import dev.wahha.wtube.ui.components.TabResetEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabResetTest {
    @get:Rule
    val compose = createComposeRule()

    // A second tap on the open tab scrolls its list back to the top, once per tap.
    @Test
    fun tapOnOpenTabScrollsToTop() {
        val tab = SavedStateHandle()
        val state = LazyListState()
        var resets = 0
        compose.setContent {
            CompositionLocalProvider(LocalTabState provides tab) {
                LazyColumn(Modifier.height(200.dp).testTag("list"), state = state) {
                    items(100) { Text("item $it", Modifier.height(40.dp)) }
                }
                TabResetEffect {
                    resets++
                    state.animateScrollToItem(0)
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(80)
        compose.runOnIdle { assertNotEquals(0, state.firstVisibleItemIndex) }

        compose.runOnIdle { tab[TAB_RESET] = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, resets)
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(false, tab.get<Boolean>(TAB_RESET))
        }

        compose.onNodeWithTag("list").performScrollToIndex(50)
        compose.runOnIdle { tab[TAB_RESET] = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(2, resets)
        }
    }
}
