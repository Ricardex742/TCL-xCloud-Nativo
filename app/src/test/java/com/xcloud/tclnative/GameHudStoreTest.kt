package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.touch.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class GameHudStoreTest {
    @Test fun perGameLayoutsPersistAcrossRecreationWithoutChangingOtherGames() {
        val first=Robolectric.buildActivity(Activity::class.java).setup()
        val settings=HudSettings(HudPreset.CALL_OF_DUTY,75,115,160,
            mapOf("fire" to HudPlacement(.8f,.4f,1.2f,HudBinding.RT)))
        GameHudStore(first.get()).save("CALLOFDUTY",settings)
        first.pause().stop().destroy()
        val next=Robolectric.buildActivity(Activity::class.java).setup()
        val store=GameHudStore(next.get())
        assertEquals(settings,store.read("callofduty"))
        assertEquals(HudSettings(),store.read("FORZAHORIZON6"))
        store.save("CALLOFDUTY",HudSettings())
        assertEquals(HudSettings(),GameHudStore(next.get()).read("CALLOFDUTY"))
        next.pause().stop().destroy()
    }
    @Test fun removedFortnitePresetsDiscardTheirBindingsWithoutChangingOtherGames() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get();val store=GameHudStore(activity)
        val other=HudSettings(preset=HudPreset.HADES,opacity=75)
        store.save("HADES",other)
        for(preset in listOf("FORTNITE","FORTNITE_BUILD")) {
            val prefs=activity.getSharedPreferences("hud_"+GameLibrary.accountKey(preset),0)
            prefs.edit().putString("layout","""{"preset":"$preset","opacity":90,"controls":{"fire":{"x":0.8,"y":0.4,"binding":"RT"}}}""").commit()
            assertEquals(HudSettings(),store.read(preset))
            assertFalse(prefs.contains("layout"))
        }
        assertEquals(other,store.read("HADES"))
        controller.pause().stop().destroy()
    }
    @Test fun invalidStoredCoordinatesAreIgnoredAndOutOfRangeValuesAreClamped() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().getSharedPreferences("hud_"+GameLibrary.accountKey("CORRUPT"),0).edit().putString("layout",
            """{"preset":"future","opacity":999,"scale":1,"sensitivity":900,"controls":{"missing":{"x":0.2},"fire":{"x":2,"y":-1,"scale":99}}}""").commit()
        val restored=GameHudStore(controller.get()).read("CORRUPT")
        assertNull(restored.preset); assertEquals(100,restored.opacity); assertEquals(75,restored.scale)
        assertEquals(900,restored.sensitivity); assertFalse("missing" in restored.placements)
        assertEquals(1f,restored.placements["fire"]!!.x,0f); assertEquals(0f,restored.placements["fire"]!!.y,0f)
        controller.pause().stop().destroy()
    }
    @Test fun chordsGesturesAndFootballSchemeSurviveRecreationAndOldLayoutsStillLoad() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        val store=GameHudStore(activity.get())
        val setting=HudSettings(sensitivity=1200,lookDeadzone=25,footballScheme=FootballScheme.ALTERNATE,
            placements=mapOf("shoot" to HudPlacement(.8f,.6f,action=HudAction(HudBinding.RB,HudBinding.X),
                gestures=mapOf(HudSwipe.UP to HudGesture("Cavadinha",HudAction(HudBinding.LB,HudBinding.X))))))
        store.save("FC",setting)
        assertEquals(setting,GameHudStore(activity.get()).read("fc"))
        val prefs=activity.get().getSharedPreferences("hud_"+GameLibrary.accountKey("LEGACY"),0)
        prefs.edit().putString("layout","""{"version":1,"sensitivity":220,"controls":{"fire":{"x":0.8,"y":0.4,"binding":"RT"}}}""").commit()
        val old=store.read("LEGACY")
        assertEquals(220,old.sensitivity); assertEquals(HudBinding.RT,old.placements["fire"]!!.binding)
        assertEquals(12,old.lookDeadzone)
        activity.pause().stop().destroy()
    }
    @Test fun oldNativeTouchPreferenceMovesToHudsOnceButAnExplicitExperimentalChoiceIsPreserved() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        controller.get().getSharedPreferences("stream_preferences",0).edit().clear().putString("touch_mode","NATIVE").commit()
        val store=PlaybackSettingsStore(controller.get())
        assertEquals(TouchMode.VIRTUAL,store.read().touchMode)
        store.save(store.read().copy(touchMode=TouchMode.NATIVE))
        assertEquals(TouchMode.NATIVE,PlaybackSettingsStore(controller.get()).read().touchMode)
        controller.pause().stop().destroy()
    }
}
