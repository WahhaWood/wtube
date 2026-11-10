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
import androidx.lifecycle.lifecycleScope
import dev.wahha.wtube.data.AppLanguage
import dev.wahha.wtube.data.AppLocale
import dev.wahha.wtube.data.LinkTarget
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.dpi.Dpi
import dev.wahha.wtube.player.LocalPlayerConnection
import dev.wahha.wtube.player.PlayerConnection
import dev.wahha.wtube.ui.WTubeApp
import dev.wahha.wtube.ui.theme.WTubeTheme
import java.util.Locale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var connection: PlayerConnection
    private lateinit var pictureInPicture: PictureInPicture
    private var deepLink by mutableStateOf<LinkTarget?>(null)

    /** The locale this activity's resources were made in; another one in the settings recreates it. */
    private lateinit var builtIn: Locale

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
        if (savedInstanceState == null) deepLink = linkOf(intent)
        followLanguage()
        setContent {
            WTubeTheme {
                CompositionLocalProvider(
                    LocalPlayerConnection provides connection,
                    LocalPictureInPicture provides pictureInPicture,
                ) {
                    WTubeApp(deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
                }
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Survives configuration changes, unlike a wrapped context: plurals keep the strings' rules after rotation.
        AppLanguage.forgetSystem()
        builtIn = AppLocale
        applyOverrideConfiguration(Configuration().apply { setLocale(builtIn) })
    }

    /**
     * Keeps the interface language and the phone's per-app language (Android 13+) the same: one picked in the
     * phone's settings is taken over, one picked in the app is shown there; a change recreates the activity
     * (the player lives in its service and goes on).
     */
    private fun followLanguage() {
        val stored = Settings.current.value.appLanguage
        AppLanguage.fromSystemSettings(this)?.takeIf { it != stored }?.let { Settings.set(Settings.AppLanguageChoice, it) }
        lifecycleScope.launch {
            Settings.current.map { it.appLanguage }.distinctUntilChanged().drop(1).collect { choice ->
                AppLanguage.toSystemSettings(this@MainActivity, choice)
                if (AppLocale != builtIn) recreate()
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pictureInPicture.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        linkOf(intent)?.let { deepLink = it }
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
    private fun linkOf(intent: Intent?): LinkTarget? {
        val text = when (intent?.action) {
            Dpi.ACTION_OPEN -> return LinkTarget.Bypass
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return null
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        return YouTube.linkTarget(url)
    }
}
