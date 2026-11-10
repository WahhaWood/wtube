package dev.wahha.wtube.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp

/** The status bar or the camera cutout, whichever reaches further down. */
@Composable
fun systemTop(): Dp = WindowInsets.statusBars.union(WindowInsets.displayCutout).asPaddingValues().calculateTopPadding()

/** The navigation bar or the camera cutout, whichever reaches further up. */
@Composable
fun systemBottom(): Dp = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues().calculateBottomPadding()

/** The navigation bar or the camera cutout at the window's end side, on a phone or tablet on its side. */
@Composable
fun systemEnd(): Dp = WindowInsets.navigationBars.union(WindowInsets.displayCutout).asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
