package dev.wahha.wtube.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    /** Highest quality picked automatically on Wi-Fi, by the short side in pixels; 0 = no limit. */
    val maxQuality: Int = 0,
    /** The same on metered networks. */
    val maxQualityMobile: Int = 480,
    /** The last speed picked in the player; new videos start at it. */
    val speed: Float = 1f,
    val subtitles: Boolean = false,
    /** Language of the last caption track picked, preferred when a new video starts with captions on. */
    val subtitleLanguage: String = "ru",
    /** Index into [Settings.SubtitleScales]. */
    val subtitleSize: Int = 1,
    val subtitleBackground: Boolean = true,
    val doubleTapSeconds: Int = 10,
    val autoplay: Boolean = true,
    val resumePlayback: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val pictureInPicture: Boolean = true,
    val ambientMode: Boolean = true,
    val liquidGlass: Boolean = true,
    val saveHistory: Boolean = true,
    /** Signed in: report what is watched here to the account's YouTube history. */
    val reportWatching: Boolean = true,
    val sponsorBlock: Boolean = true,
    /** SponsorBlock categories skipped (or muted) and marked on the seek bar. */
    val sponsorCategories: Set<String> = SponsorBlock.DefaultCategories,
    val showDislikes: Boolean = true,
    /** Index into the theme's font families: 0 Inter, 1 the phone's Roboto, 2 Montserrat. */
    val font: Int = 0,
    /** Index into the theme's text scales: smaller, normal, larger. */
    val textSize: Int = 1,
    /** Black backgrounds instead of the theme's dark tint (OLED). */
    val pureBlack: Boolean = false,
    /** Leave videos played to their end out of the subscriptions feed. */
    val hideWatched: Boolean = false,
    /** Tint the player page and mini player with the current video's cover. */
    val coverAccent: Boolean = true,
    /** Community titles from DeArrow instead of clickbait on the video page. */
    val deArrow: Boolean = false,
    /** Look for a newer release on GitHub at start. */
    val updateCheck: Boolean = true,
)

/** User settings in DataStore, mirrored into [current] so the player and UI read them synchronously. */
object Settings {
    val MaxQuality = intPreferencesKey("max_quality")
    val MaxQualityMobile = intPreferencesKey("max_quality_mobile")
    val Speed = floatPreferencesKey("speed")
    val Subtitles = booleanPreferencesKey("subtitles")
    val SubtitleLanguage = stringPreferencesKey("subtitle_language")
    val SubtitleSize = intPreferencesKey("subtitle_size")
    val SubtitleBackground = booleanPreferencesKey("subtitle_background")
    val DoubleTapSeconds = intPreferencesKey("double_tap_seconds")
    val Autoplay = booleanPreferencesKey("autoplay")
    val ResumePlayback = booleanPreferencesKey("resume_playback")
    val BackgroundPlayback = booleanPreferencesKey("background_playback")
    val PictureInPicture = booleanPreferencesKey("picture_in_picture")
    val AmbientMode = booleanPreferencesKey("ambient_mode")
    val LiquidGlass = booleanPreferencesKey("liquid_glass")
    val SaveHistory = booleanPreferencesKey("save_history")
    val ReportWatching = booleanPreferencesKey("report_watching")
    val SponsorBlockOn = booleanPreferencesKey("sponsor_block")
    val SponsorCategories = stringSetPreferencesKey("sponsor_categories")
    val ShowDislikes = booleanPreferencesKey("show_dislikes")
    val Font = intPreferencesKey("font")
    val TextSize = intPreferencesKey("text_size")
    val PureBlack = booleanPreferencesKey("pure_black")
    val HideWatched = booleanPreferencesKey("hide_watched")
    val CoverAccent = booleanPreferencesKey("cover_accent")
    val UpdateCheck = booleanPreferencesKey("update_check")
    val DeArrowOn = booleanPreferencesKey("dearrow")

    // Before 0.3 metered networks had an on/off 480p cap instead of their own quality.
    private val MobileDataSaver = booleanPreferencesKey("mobile_data_saver")

    /** Choices for [MaxQuality] and [MaxQualityMobile], 0 first. */
    val QualityChoices = listOf(0, 2160, 1440, 1080, 720, 480, 360)
    val DoubleTapChoices = listOf(5, 10, 15, 30)

    /** Caption text size per [AppSettings.subtitleSize], relative to the default. */
    val SubtitleScales = listOf(0.8f, 1f, 1.3f, 1.6f)

    private val Context.dataStore by preferencesDataStore("settings", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(AppSettings())
    private var store: DataStore<Preferences>? = null

    val current: StateFlow<AppSettings> = state.asStateFlow()

    fun init(context: Context) {
        val store = context.applicationContext.dataStore.also { store = it }
        scope.launch { store.data.collect { state.value = it.toSettings() } }
    }

    fun <T> set(key: Preferences.Key<T>, value: T) {
        val store = store ?: return
        scope.launch { store.edit { it[key] = value } }
    }

    /** Replaces every setting with [settings] at once (a restored backup). */
    suspend fun restore(settings: AppSettings) {
        val store = store ?: return
        store.edit { prefs ->
            prefs[MaxQuality] = settings.maxQuality
            prefs[MaxQualityMobile] = settings.maxQualityMobile
            prefs[Speed] = settings.speed
            prefs[Subtitles] = settings.subtitles
            prefs[SubtitleLanguage] = settings.subtitleLanguage
            prefs[SubtitleSize] = settings.subtitleSize
            prefs[SubtitleBackground] = settings.subtitleBackground
            prefs[DoubleTapSeconds] = settings.doubleTapSeconds
            prefs[Autoplay] = settings.autoplay
            prefs[ResumePlayback] = settings.resumePlayback
            prefs[BackgroundPlayback] = settings.backgroundPlayback
            prefs[PictureInPicture] = settings.pictureInPicture
            prefs[AmbientMode] = settings.ambientMode
            prefs[LiquidGlass] = settings.liquidGlass
            prefs[SaveHistory] = settings.saveHistory
            prefs[ReportWatching] = settings.reportWatching
            prefs[SponsorBlockOn] = settings.sponsorBlock
            prefs[SponsorCategories] = settings.sponsorCategories
            prefs[ShowDislikes] = settings.showDislikes
            prefs[Font] = settings.font
            prefs[TextSize] = settings.textSize
            prefs[PureBlack] = settings.pureBlack
            prefs[HideWatched] = settings.hideWatched
            prefs[CoverAccent] = settings.coverAccent
            prefs[UpdateCheck] = settings.updateCheck
            prefs[DeArrowOn] = settings.deArrow
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            maxQuality = this[MaxQuality] ?: defaults.maxQuality,
            maxQualityMobile = this[MaxQualityMobile] ?: if (this[MobileDataSaver] == false) 0 else defaults.maxQualityMobile,
            speed = this[Speed] ?: defaults.speed,
            subtitles = this[Subtitles] ?: defaults.subtitles,
            subtitleLanguage = this[SubtitleLanguage] ?: defaults.subtitleLanguage,
            subtitleSize = (this[SubtitleSize] ?: defaults.subtitleSize).coerceIn(SubtitleScales.indices),
            subtitleBackground = this[SubtitleBackground] ?: defaults.subtitleBackground,
            doubleTapSeconds = this[DoubleTapSeconds] ?: defaults.doubleTapSeconds,
            autoplay = this[Autoplay] ?: defaults.autoplay,
            resumePlayback = this[ResumePlayback] ?: defaults.resumePlayback,
            backgroundPlayback = this[BackgroundPlayback] ?: defaults.backgroundPlayback,
            pictureInPicture = this[PictureInPicture] ?: defaults.pictureInPicture,
            ambientMode = this[AmbientMode] ?: defaults.ambientMode,
            liquidGlass = this[LiquidGlass] ?: defaults.liquidGlass,
            saveHistory = this[SaveHistory] ?: defaults.saveHistory,
            reportWatching = this[ReportWatching] ?: defaults.reportWatching,
            sponsorBlock = this[SponsorBlockOn] ?: defaults.sponsorBlock,
            sponsorCategories = this[SponsorCategories] ?: defaults.sponsorCategories,
            showDislikes = this[ShowDislikes] ?: defaults.showDislikes,
            font = this[Font] ?: defaults.font,
            textSize = this[TextSize] ?: defaults.textSize,
            pureBlack = this[PureBlack] ?: defaults.pureBlack,
            hideWatched = this[HideWatched] ?: defaults.hideWatched,
            coverAccent = this[CoverAccent] ?: defaults.coverAccent,
            updateCheck = this[UpdateCheck] ?: defaults.updateCheck,
            deArrow = this[DeArrowOn] ?: defaults.deArrow,
        )
    }
}

/** The automatic quality cap for the current network, by the short side in pixels; null = none. */
fun qualityCap(settings: AppSettings, metered: Boolean): Int? =
    (if (metered) settings.maxQualityMobile else settings.maxQuality).takeIf { it > 0 }
