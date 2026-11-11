package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.renderer.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackDisplayPreferenceTest {
    private val phone90 = OutputMode(1, 1080, 2340, 90f)
    private val phone60 = phone90.copy(id = 2, hz = 60f)
    private val phone120 = phone90.copy(id = 3, hz = 120f)

    @Test fun sixtyFpsChoosesSixtyInsteadOfNinetyWithoutChangingResolution() {
        val otherSize = OutputMode(4, 720, 1560, 60f)
        assertEquals(2, DisplayModePolicy.preferredCadence(phone90, listOf(otherSize, phone120, phone60), 60))
        assertNull(DisplayModePolicy.preferredCadence(phone90, listOf(otherSize), 60))
    }

    @Test fun matchingMultiplesAndFractionalRefreshRatesArePreserved() {
        for (hz in listOf(59.94f, 60f, 119.88f, 120f)) {
            val current = phone90.copy(hz = hz)
            assertEquals(current.id, DisplayModePolicy.preferredCadence(current, listOf(phone60), 60))
        }
        assertEquals(1, DisplayModePolicy.preferredCadence(phone90, listOf(phone60), 30))
    }

    @Test fun unsupportedOrInvalidCadencesDoNotInventAMode() {
        assertNull(DisplayModePolicy.preferredCadence(phone90, listOf(phone60.copy(hz = 48f)), 60))
        assertNull(DisplayModePolicy.preferredCadence(phone90, listOf(phone60.copy(hz = Float.NaN)), 60))
        assertNull(DisplayModePolicy.preferredCadence(phone90, listOf(phone60), 0))
    }

    @Test fun replacementAndReleaseRestoreBothOriginalPreferencesAndKeepOtherWindowChanges() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window = controller.get().window
            window.attributes = window.attributes.apply { preferredDisplayModeId = 7; preferredRefreshRate = 90f }
            val preference = PlaybackDisplayPreference(window)
            preference.request(2)
            assertEquals(2, window.attributes.preferredDisplayModeId)
            assertEquals(0f, window.attributes.preferredRefreshRate, 0f)
            preference.request(3)
            window.attributes = window.attributes.apply { screenBrightness = 0.4f }
            preference.restore()
            preference.restore()
            assertEquals(7, window.attributes.preferredDisplayModeId)
            assertEquals(90f, window.attributes.preferredRefreshRate, 0f)
            assertEquals(0.4f, window.attributes.screenBrightness, 0f)
            preference.request(2)
            preference.request(null)
            assertEquals(7, window.attributes.preferredDisplayModeId)
            assertEquals(90f, window.attributes.preferredRefreshRate, 0f)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun diagnosticsDistinguishTheWindowRequestFromTheUnchangedPhysicalModeAndListAvailableModes() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val window=controller.get().window
            val display=window.decorView.display
            val actual=display.mode.modeId
            val preference=PlaybackDisplayPreference(window)
            preference.request(12345)
            val report=preference.diagnostics(display)
            assertTrue(report,report.contains("pedido: #12345 não anunciado"))
            assertTrue(report,report.contains("físico atual: #$actual "))
            assertTrue(report,report.contains("taxa informada ao app:"))
            assertTrue(report,report.contains("Modos anunciados: #$actual "))
            assertEquals(actual,display.mode.modeId)
            assertEquals("Modo de tela: n/d",preference.diagnostics(null))
        } finally { controller.pause().stop().destroy() }
    }
}
