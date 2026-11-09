package com.xcloud.tclnative

import android.app.Activity
import android.view.Display
import android.view.View
import android.view.ViewGroup
import com.xcloud.tclnative.renderer.HdrStatus
import com.xcloud.tclnative.ui.dashboard.PlaybackSettingsPanel
import com.xcloud.tclnative.settings.PlaybackSettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HdrStatusTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun hdr10DisplaySupportDoesNotForceHdrForTheEightBitSdrStream() {
        assertTrue(HdrStatus.displayLabel(intArrayOf(Display.HdrCapabilities.HDR_TYPE_HDR10)).contains("HDR10 anunciado"))
        assertTrue(HdrStatus.displayLabel(intArrayOf(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)).contains("não anunciado"))
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); val before=activity.window.colorMode
        val panel=PlaybackSettingsPanel(activity,PlaybackSettingsStore(activity),{},{},{},{})
        val dialog=panel.showHdrStatus()
        val text=all(dialog.body).filterIsInstance<android.widget.TextView>().joinToString { it.text }
        assertTrue(text.contains("H.264 de 8 bits")); assertTrue(text.contains("SDR")); assertTrue(text.contains("BT.2020"))
        assertEquals(before,activity.window.colorMode)
        dialog.dismiss(); controller.pause().stop().destroy()
    }
}
