package dev.wahha.wtube

import dev.wahha.wtube.data.AppSettings
import dev.wahha.wtube.data.qualityCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsTest {
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
}
