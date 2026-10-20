package dev.wahha.wtube

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.player.LocalPlayerConnection
import dev.wahha.wtube.player.PlayerConnection
import dev.wahha.wtube.ui.WTubeApp
import dev.wahha.wtube.ui.theme.WTubeTheme

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlayerConnection
    private lateinit var pictureInPicture: PictureInPicture
    private var deepLinkVideo by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark-only app: light system bar icons whatever the system theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        connection = PlayerConnection(this)
        pictureInPicture = PictureInPicture(this)
        addOnPictureInPictureModeChangedListener { info ->
            pictureInPicture.onModeChanged(info.isInPictureInPictureMode)
            // Closing the floating window stops the activity; silence the video too, as YouTube does.
            if (!info.isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                connection.player?.pause()
            }
        }
        if (savedInstanceState == null) deepLinkVideo = videoIdOf(intent)
        setContent {
            WTubeTheme {
                CompositionLocalProvider(
                    LocalPlayerConnection provides connection,
                    LocalPictureInPicture provides pictureInPicture,
                ) {
                    WTubeApp(deepLinkVideo = deepLinkVideo, onDeepLinkHandled = { deepLinkVideo = null })
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Survives configuration changes, unlike a wrapped context: plurals keep Russian rules after rotation.
        applyOverrideConfiguration(Configuration().apply { setLocale(AppLocale) })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pictureInPicture.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        videoIdOf(intent)?.let { deepLinkVideo = it }
    }

    override fun onStop() {
        super.onStop()
        // With background playback off, leaving the app pauses (picture-in-picture keeps the activity started).
        if (!Settings.current.value.backgroundPlayback && !isChangingConfigurations) connection.player?.pause()
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }

    /** A YouTube link opened with the app (VIEW) or shared to it as text (SEND). */
    private fun videoIdOf(intent: Intent?): String? {
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return null
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        return YouTube.videoIdOf(url)
    }
}
