package dev.wahha.wtube

import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.capLongSide
import dev.wahha.wtube.data.qualityCap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
    @Test
    fun capLongSideKeepsTheStandardWidths() {
        val expected = mapOf(144 to 256, 240 to 427, 360 to 640, 480 to 854, 720 to 1280, 1080 to 1920, 1440 to 2560, 2160 to 3840)
        expected.forEach { (cap, width) -> assertEquals("cap $cap", width, capLongSide(cap)) }
    }

    @Test
    fun noCapByDefaultOnWifi() {
        assertNull(qualityCap(AppSettings(), metered = false))
    }

    @Test
    fun meteredNetworksDefaultTo480() {
        assertEquals(480, qualityCap(AppSettings(), metered = true))
        assertNull(qualityCap(AppSettings(maxQualityMobile = 0), metered = true))
    }

    @Test
    fun eachNetworkHasItsOwnCap() {
        val settings = AppSettings(maxQuality = 1080, maxQualityMobile = 720)
        assertEquals(1080, qualityCap(settings, metered = false))
        assertEquals(720, qualityCap(settings, metered = true))
        assertEquals(2160, qualityCap(AppSettings(maxQuality = 360, maxQualityMobile = 2160), metered = true))
    }

    // Every field away from its default, so a field left out of the store's write or read comes back changed.
    private val changed = AppSettings(
        maxQuality = 1080, maxQualityMobile = 360, speed = 1.5f, subtitles = true, subtitleLanguage = "en",
        subtitleSize = 3, subtitleBackground = false, doubleTapSeconds = 30, autoplay = false,
        resumePlayback = false, backgroundPlayback = false, pictureInPicture = false, ambientMode = false,
        liquidGlass = false, saveHistory = false, reportWatching = false, sponsorBlock = false,
        sponsorCategories = setOf("intro"), showDislikes = false, font = 2, textSize = 0, pureBlack = true,
        hideWatched = true, coverAccent = false, deArrow = true, updateCheck = false, dpiBypass = true,
        gestureBrightness = false, gestureVolume = false, gestureSwapSides = true, gestureZone = 2, gestureSensitivity = 0,
        gestureRememberBrightness = true, gestureBrightnessLevel = 0.4f, gestureHaptics = false,
    )

    @Test
    fun fixtureChangesEveryField() {
        val json = Json { encodeDefaults = true }
        val defaults = json.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject
        val all = json.encodeToJsonElement(AppSettings.serializer(), changed).jsonObject
        for ((name, value) in all) assertNotEquals("$name is left at its default here", defaults[name], value)
    }

    @Test
    fun everySettingSurvivesTheStore() = with(Settings) {
        val prefs = mutablePreferencesOf()
        prefs.write(changed)
        assertEquals(changed, prefs.toSettings())
    }

    @Test
    fun emptyStoreGivesDefaults() = with(Settings) {
        assertEquals(AppSettings(), mutablePreferencesOf().toSettings())
    }
}
