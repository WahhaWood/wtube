package dev.wahha.wtube.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/** The status bar or the camera cutout, whichever reaches further down. */
@Composable
fun systemTop(): Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()

/** The navigation bar or the camera cutout, whichever reaches further up. */
@Composable
fun systemBottom(): Dp = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues().calculateBottomPadding()
