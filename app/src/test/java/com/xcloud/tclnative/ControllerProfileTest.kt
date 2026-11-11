package com.xcloud.tclnative

import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.GamepadState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ControllerProfileTest {
    @Test fun defaultKeepsTheExistingAxesIncludingSquareDiagonalsAndDigitalState() {
        val input=GamepadState(16 or 4096,1f,1f,.02f,-.7f,.1f,1f)
        assertEquals(input,ControllerProfile().apply(input))
    }
    @Test fun deadzonesCurvesSensitivityInversionAndTriggerSwapAreAppliedToNormalizedInput() {
        val input=GamepadState(leftX=.05f,leftY=0f,rightX=.5f,rightY=0f,leftTrigger=.05f,rightTrigger=1f)
        val output=ControllerProfile(leftDeadzone=10,curve=StickCurve.PRECISE,triggerDeadzone=10,swapTriggers=true).apply(input)
        assertEquals(0f,output.leftX,0f)
        assertTrue(output.rightX in .2f.. .4f)
        assertEquals(1f,output.leftTrigger,0f);assertEquals(0f,output.rightTrigger,0f)
        val inverted=ControllerProfile(invertRightY=true,rightSensitivity=200).apply(GamepadState(rightY=.8f))
        assertEquals(-1f,inverted.rightY,0f)
        assertEquals(1f,ControllerProfile(rightDeadzone=20).apply(GamepadState(rightX=1f)).rightX,0f)
    }
    @Test fun remappingPreservesOtherHeldSourcesAndReleasesDoNotGetStuck() {
        val profile=ControllerProfile(mapping=mapOf(16 to 32,64 to 0))
        assertEquals(32,profile.apply(GamepadState(buttons=16 or 32 or 64)).buttons)
        assertEquals(32,profile.apply(GamepadState(buttons=32)).buttons)
        assertEquals(0,profile.apply(GamepadState()).buttons)
    }
    @Test fun profilesRemainSeparateAndSurviveReopeningWithoutChangingDefaultMapping() {
        val context=RuntimeEnvironment.getApplication();val store=ControllerProfileStore(context)
        val profile=ControllerProfile(rightDeadzone=5,rightSensitivity=125,curve=StickCurve.PRECISE,mapping=mapOf(16 to 32))
        store.save("gamesir-one",profile)
        assertEquals(profile,ControllerProfileStore(context).read("gamesir-one"))
        assertEquals(ControllerProfile(),store.read("gamesir-two"))
        store.reset("gamesir-one")
        assertEquals(ControllerProfile(),ControllerProfileStore(context).read("gamesir-one"))
    }
}
