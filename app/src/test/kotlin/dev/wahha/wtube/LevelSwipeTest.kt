package dev.wahha.wtube

import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.ui.video.LevelKind
import dev.wahha.wtube.ui.video.isLevelDrag
import dev.wahha.wtube.ui.video.levelAfter
import dev.wahha.wtube.ui.video.levelSwipeAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LevelSwipeTest {
    private val width = 2000f
    private val height = 1000f
    private val edge = 100f

    private fun at(x: Float, y: Float = 500f, settings: AppSettings = AppSettings()) = levelSwipeAt(x, y, width, height, edge, settings)

    @Test
    fun brightnessLeftVolumeRightMiddleForTheSheet() {
        assertEquals(LevelKind.Brightness, at(100f))
        assertEquals(LevelKind.Volume, at(1900f))
        // A third of the width by default: the middle swipes down out of fullscreen as before.
        assertNull(at(1000f))
        assertNull(at(700f))
    }

    @Test
    fun topAndBottomStripsAreNeverLevels() {
        assertNull(at(100f, y = 50f))
        assertNull(at(1900f, y = 950f))
    }

    @Test
    fun followsSettings() {
        assertEquals(LevelKind.Volume, at(100f, settings = AppSettings(gestureSwapSides = true)))
        assertEquals(LevelKind.Brightness, at(1900f, settings = AppSettings(gestureSwapSides = true)))
        assertNull(at(100f, settings = AppSettings(gestureBrightness = false)))
        assertEquals(LevelKind.Volume, at(1900f, settings = AppSettings(gestureBrightness = false)))
        assertNull(at(1900f, settings = AppSettings(gestureVolume = false)))
        // Narrow zones are a quarter, wide ones half the width.
        assertNull(at(600f, settings = AppSettings(gestureZone = 0)))
        assertEquals(LevelKind.Brightness, at(900f, settings = AppSettings(gestureZone = 2)))
    }

    @Test
    fun onlyClearlyVerticalDrags() {
        assertTrue(isLevelDrag(dx = 5f, dy = -40f))
        assertFalse(isLevelDrag(dx = 30f, dy = -40f))
        assertFalse(isLevelDrag(dx = 40f, dy = 10f))
    }

    @Test
    fun sensitivitySetsTheSwipeLength() {
        // Up is negative: 0.8 of the height from 0 to 100% at normal sensitivity.
        assertEquals(0.5f, levelAfter(0f, -400f, height, AppSettings()), 1e-4f)
        assertEquals(1f, levelAfter(0f, -500f, height, AppSettings(gestureSensitivity = 2)), 1e-4f)
        assertEquals(0.25f, levelAfter(0.5f, 300f, height, AppSettings(gestureSensitivity = 0)), 1e-4f)
        assertEquals(0.01f, levelAfter(0.1f, 900f, height, AppSettings(), min = 0.01f), 1e-4f)
    }
}
