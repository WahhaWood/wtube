package dev.wahha.wtube

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.wahha.wtube.ui.components.FeedGrid
import dev.wahha.wtube.ui.components.LocalWindowLayout
import dev.wahha.wtube.ui.components.WindowLayout
import dev.wahha.wtube.ui.components.fullRow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A feed laid out in a real window of each size: the columns and the card width the user sees. */
@RunWith(AndroidJUnit4::class)
class FeedGridTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = LazyGridState()

    @Test
    @Config(qualifiers = "w411dp-h914dp")
    fun phoneUprightKeepsOneEdgeToEdgeColumn() {
        showFeed()
        assertEquals(1, columns())
        assertCard(left = 0.dp, width = 411.dp)
    }

    @Test
    @Config(qualifiers = "w640dp-h800dp")
    fun halfATabletStaysOneColumn() {
        showFeed()
        assertEquals(1, columns())
        assertCard(left = 0.dp, width = 640.dp)
    }

    @Test
    @Config(qualifiers = "w840dp-h1200dp")
    fun tabletUprightGetsTwoColumns() {
        showFeed()
        assertEquals(2, columns())
        assertCard(left = 16.dp, width = (840.dp - 16.dp * 3) / 2)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun tabletOnItsSideGetsThreeColumns() {
        showFeed()
        assertEquals(3, columns())
        assertCard(left = 16.dp, width = (1280.dp - 16.dp * 4) / 3)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun headerSpansTheWholeRow() {
        showFeed()
        val header = compose.onNodeWithTag("header").getUnclippedBoundsInRoot()
        assertEquals(1280 - 32f, (header.right - header.left).value, 1f)
    }

    // The window's layout comes from the root's constraints, as in WTubeApp.
    private fun showFeed() {
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalWindowLayout provides WindowLayout(maxWidth, maxHeight)) {
                    FeedGrid(Modifier.fillMaxSize(), state = state) {
                        fullRow(key = "header") { Box(Modifier.fillMaxWidth().height(40.dp).testTag("header")) }
                        items(30) { Box(Modifier.fillMaxWidth().height(200.dp).testTag("card$it")) }
                    }
                }
            }
        }
    }

    private fun columns(): Int = compose.runOnIdle { state.layoutInfo.maxSpan }

    private fun assertCard(left: Dp, width: Dp) {
        val card = compose.onNodeWithTag("card0").getUnclippedBoundsInRoot()
        assertEquals(left.value, card.left.value, 1f)
        assertEquals(width.value, (card.right - card.left).value, 1f)
    }
}
