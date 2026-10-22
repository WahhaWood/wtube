package dev.wahha.wtube.ui.video

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings as SystemSettings
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.areSystemBarsVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

/** Bars shown by a swipe from the edge, or put back by the system, hide again after this. */
private const val REHIDE_MS = 2_000L

/** True while the video is fullscreen: sheets and dialogs opened over it hide the system bars too. */
val LocalFullscreen = compositionLocalOf { false }

private fun WindowInsetsControllerCompat.hideSystemBars() {
    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    hide(WindowInsetsCompat.Type.systemBars())
}

/**
 * Applies [orientationLock] and hides the system bars while fullscreen; leaving undoes both. Some phones
 * bring the bars back on their own (after the notification shade, a dialog, another app on top), so they
 * are hidden again whenever the window gets its focus back or the bars stay in sight for a while.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FullscreenEffect(fullscreen: Boolean, orientationLock: Int?) {
    val activity = LocalContext.current as? Activity ?: return
    val view = LocalView.current
    DisposableEffect(orientationLock) {
        activity.requestedOrientation = orientationLock ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    DisposableEffect(fullscreen) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        if (fullscreen) controller.hideSystemBars()
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
    if (fullscreen) {
        val focused = LocalWindowInfo.current.isWindowFocused
        val barsVisible = WindowInsets.areSystemBarsVisible
        LaunchedEffect(focused, barsVisible) {
            if (!focused) return@LaunchedEffect
            if (barsVisible) delay(REHIDE_MS)
            WindowCompat.getInsetsController(activity.window, view).hideSystemBars()
        }
    }
}

/**
 * Call inside a sheet or dialog: over the fullscreen video it hides the system bars in its own window,
 * which otherwise shows them for as long as it is open.
 */
@Composable
internal fun ImmersiveDialog() {
    if (!LocalFullscreen.current) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = generateSequence(view.parent) { (it as? View)?.parent }
            .filterIsInstance<DialogWindowProvider>()
            .firstOrNull()?.window
        if (window != null) WindowCompat.getInsetsController(window, view).hideSystemBars()
        onDispose { }
    }
}

/**
 * Keeps the screen awake while [on], as the YouTube app does while a video plays. Through the window
 * flag, not View.keepScreenOn: a view's flag reaches the window only on its next layout pass, and on a
 * still screen (a video ended in fullscreen) there is none, so the screen stayed on all night.
 */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val window = (LocalContext.current as? Activity)?.window ?: return
    DisposableEffect(window, on) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Releases an orientation forced by the fullscreen button once the phone is physically turned that way,
 * as YouTube does: entered by the button and turned sideways, turning back upright leaves fullscreen;
 * left by the button while sideways, turning sideways again enters it. With auto-rotate off the lock
 * stays, since the screen would not follow the phone anyway.
 */
@Composable
internal fun ReleaseOrientationLock(orientationLock: Int?, onRelease: () -> Unit) {
    val context = LocalContext.current
    val release by rememberUpdatedState(onRelease)
    val sideways = orientationLock == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    val upright = orientationLock == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    if (!sideways && !upright) return
    DisposableEffect(context, sideways) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val autoRotate = SystemSettings.System.getInt(context.contentResolver, SystemSettings.System.ACCELEROMETER_ROTATION, 0) == 1
                if (!autoRotate) return
                val held = when (degrees) {
                    in 60..120, in 240..300 -> true
                    in 0..30, in 330..359 -> false
                    else -> return
                }
                if (held == sideways) release()
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}
