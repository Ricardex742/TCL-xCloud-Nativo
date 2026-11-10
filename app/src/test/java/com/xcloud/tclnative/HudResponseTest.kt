package com.xcloud.tclnative

import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HudResponseTest {
    private fun buttons(state: GamepadState)=ByteBuffer.wrap(GamepadPacket.encode(state,1,0.0)).order(ByteOrder.LITTLE_ENDIAN).getShort(16).toInt() and 65535
    @Test fun footballCommandsEncodeTheExpectedXboxInputsInBothSchemes() {
        for(scheme in FootballScheme.entries) {
            val expected=mapOf("pass" to 16,"through" to 128,"shoot" to if(scheme==FootballScheme.ALTERNATE) 64 else 32,
                "cross" to if(scheme==FootballScheme.ALTERNATE) 32 else 64)
            for((id,mask) in expected) {
                val input=VirtualGamepad(1452,689,HudPreset.EA_FC,HudSettings(footballScheme=scheme))
                val c=input.controls.single { it.id==id };input.down(1,c.x,c.y,0)
                assertEquals("$scheme $id",mask,buttons(input.state(0)))
                assertEquals(0f,input.state(0).rightTrigger,0f)
            }
        }
    }
    @Test fun overlappingSprintCannotStealAThroughPassAndRemappingChangesThePacket() {
        for(binding in listOf(HudBinding.Y,HudBinding.B)) {
            val input=VirtualGamepad(1452,689,HudPreset.EA_FC,HudSettings(footballScheme=FootballScheme.ALTERNATE,
                placements=mapOf("through" to HudPlacement(.755f,.55f,binding=binding),"sprint" to HudPlacement(.78f,.55f,1.7f))))
            val through=input.controls.single { it.id=="through" };val sprint=input.controls.single { it.id=="sprint" }
            assertEquals("through",input.hit(through.x,through.y)?.id)
            input.down(1,through.x,through.y,0)
            assertEquals(binding.button,buttons(input.state(0)));assertEquals(0f,input.state(0).rightTrigger,0f)
            input.down(2,sprint.x,sprint.y,1)
            assertEquals(binding.button,buttons(input.state(1)));assertEquals(1f,input.state(1).rightTrigger,0f)
            input.up(1,90);assertEquals(0,buttons(input.state(90)));assertEquals(1f,input.state(90).rightTrigger,0f)
            input.up(2,100);assertEquals(GamepadState(),input.state(100))
        }
    }
    @Test fun applyingTheFootballSchemeClearsOldCommandsButKeepsButtonGeometry() {
        val position=HudPlacement(.71f,.54f,1.2f,HudBinding.RT,HudAction(HudBinding.L3),emptyMap())
        val settings=HudSettings(placements=mapOf("through" to position)).withFootballScheme(FootballScheme.ALTERNATE)
        val saved=settings.placements.getValue("through")
        assertEquals(position.x,saved.x,0f);assertEquals(position.scale,saved.scale,0f)
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC,settings)
        val c=input.controls.single { it.id=="through" };input.down(0,c.x,c.y,0)
        assertEquals(128,buttons(input.state(0)));assertEquals(0f,input.state(0).rightTrigger,0f)
        assertEquals(64,input.controls.single { it.id=="shoot" }.action!!.buttons)
    }
    @Test fun oldAndroidTimestampsDoNotEraseARecentlyDeliveredFootballTap() {
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC)
        val c=input.controls.single { it.id=="through" }
        input.down(1,c.x,c.y,0,receivedAt=200);input.up(1,8,receivedAt=201)
        assertEquals(128,buttons(input.state(201)))
        assertEquals(264L,input.nextUpdate(201));assertEquals(128,buttons(input.state(263)))
        assertEquals(GamepadState(),input.state(264))
        input.down(1,c.x,c.y,300,300);input.up(1,301,301);input.clear()
        assertEquals(GamepadState(),input.state(302));assertNull(input.nextUpdate(302))
    }
    @Test fun fireCanOptionallyHoldAimAndEachFingerReleasesIndependently() {
        for(enabled in listOf(false,true)) {
            val input=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY,HudSettings(aimAndFire=enabled))
            val fire=input.controls.single { it.id=="fire" };val left=input.controls.single { it.id=="fire-left" }
            input.down(1,fire.x,fire.y,0);input.down(2,left.x,left.y,1)
            assertEquals(1f,input.state(1).rightTrigger,0f);assertEquals(if(enabled) 1f else 0f,input.state(1).leftTrigger,0f)
            input.up(1,2);assertEquals(1f,input.state(2).rightTrigger,0f)
            input.up(2,3);assertEquals(GamepadState(),input.state(3))
        }
    }
    @Test fun draggingAimMovesTheCameraEvenWhenAndroidDeliversTheEventLate() {
        val input=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY)
        val aim=input.controls.single { it.id=="aim" }
        input.down(1,aim.x,aim.y,0,100);input.move(1,aim.x+4,aim.y-2,16,200)
        val state=input.state(200)
        assertEquals(1f,state.leftTrigger,0f);assertEquals(0f,state.rightTrigger,0f)
        assertTrue(state.rightX>0);assertTrue(state.rightY<0)
        assertEquals(0f,input.state(232).rightX,0f);assertEquals(1f,input.state(232).leftTrigger,0f)
        input.up(1,240);assertEquals(GamepadState(),input.state(240))
    }
    @Test fun defaultCodRespondsMoreStronglyToTheSameSmallSwipeAndRespectsStickLimits() {
        fun swipe(preset: HudPreset): GamepadState {
            val input=VirtualGamepad(1280,720,preset);input.down(1,700f,350f,0);input.move(1,702f,350f,16)
            return input.state(16)
        }
        val cod=swipe(HudPreset.CALL_OF_DUTY);val generic=swipe(HudPreset.SHOOTER)
        assertTrue(cod.rightX>generic.rightX);assertTrue(cod.rightX<=1f)
    }
    @Test fun sprintGestureUsesTheStickAndAllowsJumpingWithoutReleasingTheRun() {
        val input=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY)
        val move=input.controls.single { it.id=="move" };val jump=input.controls.single { it.id=="jump" }
        input.down(1,move.x,move.y,0);input.move(1,move.x,move.y-move.radius*.8f,1)
        assertEquals(0,input.state(1).buttons)
        input.move(1,move.x,move.y-move.radius*1.6f,2)
        assertEquals(16384,input.state(2).buttons);assertEquals(-1f,input.state(2).leftY,0f)
        assertTrue(input.held(input.controls.single { it.id=="sprint" }))
        input.down(2,jump.x,jump.y,3);assertEquals(16400,input.state(3).buttons)
        input.up(2,4);assertEquals(16384,input.state(4).buttons)
        input.move(1,move.x,move.y-move.radius,5);assertEquals(0,input.state(5).buttons)
        input.up(1,6);assertEquals(GamepadState(),input.state(6))
    }
    @Test fun sidewaysMovementAndDisabledSprintDoNotTriggerAutomaticRunning() {
        for(enabled in listOf(false,true)) {
            val input=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY,HudSettings(sprintGesture=enabled))
            val move=input.controls.single { it.id=="move" }
            input.down(1,move.x,move.y,0);input.move(1,move.x+move.radius*2,move.y,1)
            assertEquals(0,input.state(1).buttons)
            if(!enabled) { input.move(1,move.x,move.y-move.radius*2,2);assertEquals(0,input.state(2).buttons) }
        }
    }
    @Test fun movementChangesWakeTheInputBufferAndOnlyTheLatestAxisPositionIsSent() {
        val buffer=GamepadInputBuffer()
        assertTrue(buffer.update(GamepadState(leftY=-.4f)));assertTrue(buffer.hasUrgent());assertFalse(buffer.hasEdges())
        buffer.update(GamepadState(leftY=-1f));assertEquals(-1f,buffer.next().leftY,0f);assertFalse(buffer.hasUrgent())
        assertTrue(buffer.update(GamepadState()));assertEquals(GamepadState(),buffer.next())
    }
    @Test fun aNewAndroidGestureDropsAnInterruptedSprintBeforeAThroughPass() {
        val input=VirtualGamepad(1452,689,HudPreset.EA_FC,HudSettings(footballScheme=FootballScheme.ALTERNATE))
        val move=input.controls.single { it.id=="move" };val sprint=input.controls.single { it.id=="sprint" };val through=input.controls.single { it.id=="through" }
        input.down(0,move.x,move.y,0);input.move(0,move.x,move.y-move.radius,1)
        input.down(1,sprint.x,sprint.y,2);assertEquals(1f,input.state(2).rightTrigger,0f)
        assertEquals(2,input.startGesture())
        input.down(0,through.x,through.y,20)
        assertEquals(128,buttons(input.state(20)));assertEquals(0f,input.state(20).rightTrigger,0f)
        assertEquals(0f,input.state(20).leftY,0f)
    }

}
