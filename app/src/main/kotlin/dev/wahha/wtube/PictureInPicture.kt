package dev.wahha.wtube

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Picture-in-picture for the video screen: it calls [setEnabled] while a video plays, and leaving the app
 * then shrinks the activity into a floating window (automatically on Android 12+, from
 * [onUserLeaveHint] before that). [active] tells the UI to draw only the video.
 */
class PictureInPicture(private val activity: ComponentActivity) {
    var active by mutableStateOf(false)
        private set
    private var enabled = false
    private val supported = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun setEnabled(value: Boolean) {
        if (!supported || enabled == value) return
        enabled = value
        // The player's dispose turns it off while the activity is being destroyed, and the system then
        // throws "Can't find activity for token": nothing is left to set it on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !activity.isDestroyed && !activity.isFinishing) {
            runCatching { activity.setPictureInPictureParams(params()) }
        }
    }

    fun onUserLeaveHint() {
        if (supported && enabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            runCatching { activity.enterPictureInPictureMode(params()) }
        }
    }

    fun onModeChanged(inPictureInPicture: Boolean) {
        active = inPictureInPicture
    }

    private fun params(): PictureInPictureParams = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(enabled) }
        .build()
}

val LocalPictureInPicture = staticCompositionLocalOf<PictureInPicture?> { null }
