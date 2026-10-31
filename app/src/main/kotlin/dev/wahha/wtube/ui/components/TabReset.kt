package dev.wahha.wtube.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.filter

const val TAB_RESET = "tab_reset"

/** The tab page's own back stack entry state; the bar sets [TAB_RESET] in it when the open tab is tapped again. */
val LocalTabState = staticCompositionLocalOf<SavedStateHandle?> { null }

/** Runs [onReset] when the open tab is tapped again in the bar, to bring the page back to its start. */
@Composable
fun TabResetEffect(onReset: suspend () -> Unit) {
    val state = LocalTabState.current ?: return
    val current by rememberUpdatedState(onReset)
    // Collected, not keyed on the flag: clearing it must not cancel the scroll it started.
    LaunchedEffect(state) {
        state.getStateFlow(TAB_RESET, false).filter { it }.collect {
            state[TAB_RESET] = false
            current()
        }
    }
}
