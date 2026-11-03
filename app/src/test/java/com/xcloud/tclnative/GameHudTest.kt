package com.xcloud.tclnative

import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test

class GameHudTest {
    @Test fun legacyShotRemappingAlsoChangesTheDefaultSwipeModifiers() {
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC,HudSettings(placements=mapOf(
            "shoot" to HudPlacement(.91f,.54f,binding=HudBinding.X))))
        val shot=input.controls.single { it.id=="shoot" }
        input.down(1,shot.x,shot.y,0); input.move(1,shot.x,shot.y+40,16)
        assertEquals(8256,input.state(16).buttons)
    }
    @Test fun rectangularButtonsAcceptTheirVisibleEdgesAndFitBeforeTheFirstLayout() {
        VirtualGamepad(1,1,HudPreset.EA_FC)
        val fc=VirtualGamepad(1280,720,HudPreset.EA_FC)
        val chip=fc.controls.single { it.id=="football-mode" }
        fc.down(1,chip.x+chip.radius*1.7f,chip.y,0); fc.up(1,1)
        assertEquals(HudPreset.EA_FC_DEFENSE,fc.preset)
        val build=VirtualGamepad(1280,720,HudPreset.FORTNITE_BUILD)
        val wall=build.controls.single { it.id=="wall" }
        build.down(2,wall.x+wall.radius*.9f,wall.y+wall.radius*.9f,0)
        assertEquals(1f,build.state(0).rightTrigger,0f)
    }
    @Test fun footballShotSwipesProduceTheActualModifiersInBothConsoleSchemesAndReleaseThem() {
        for(scheme in FootballScheme.entries) for(direction in HudSwipe.entries) {
            val input=VirtualGamepad(1280,720,HudPreset.EA_FC,HudSettings(footballScheme=scheme))
            val control=input.controls.single { it.id=="shoot" }
            val shot=if(scheme==FootballScheme.CLASSIC) 32 else 64
            input.down(1,control.x,control.y,0)
            val x=control.x+if(direction==HudSwipe.LEFT) -40 else if(direction==HudSwipe.RIGHT) 40 else 0
            val y=control.y+if(direction==HudSwipe.UP) -40 else if(direction==HudSwipe.DOWN) 40 else 0
            input.move(1,x,y,16)
            val modifiers=when(direction) { HudSwipe.UP -> 4096; HudSwipe.DOWN -> 8192; else -> 12288 }
            assertEquals(shot or modifiers,input.state(16).buttons)
            input.up(1,20)
            assertEquals(shot or modifiers,input.state(40).buttons)
            assertNotNull(input.nextUpdate(40))
            assertEquals(GamepadState(),input.state(81)); assertNull(input.nextUpdate(81))
        }
    }
    @Test fun footballContextChangesLabelsAndActionsWithoutSendingTheModeSwitchToTheGame() {
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC)
        val move=input.controls.single { it.id=="move" }
        input.down(1,move.x,move.y,0); input.move(1,move.x+move.radius,move.y,8)
        val mode=input.controls.single { it.id=="football-mode" }
        input.down(2,mode.x,mode.y,9); assertEquals(0,input.state(9).buttons)
        input.up(2,10)
        assertEquals(HudPreset.EA_FC_DEFENSE,input.preset)
        assertEquals(1f,input.state(10).leftX,.0001f)
        assertFalse(input.controls.any { it.id=="shoot" || it.id=="pass" })
        val mark=input.controls.single { it.id=="defend-contain" }
        input.down(3,mark.x,mark.y,11)
        val state=input.state(11)
        assertEquals(16,state.buttons); assertEquals(1f,state.rightTrigger,0f)
        input.up(3,12); assertEquals(0,input.state(12).buttons); assertEquals(0f,input.state(12).rightTrigger,0f)
        input.clear(); assertEquals(GamepadState(),input.state(13))
    }
    @Test fun cameraHasHigherGainAndDeadzoneCompensationAndStopsWithin32ms() {
        fun swipe(settings: HudSettings): VirtualGamepad = VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY,settings).apply {
            down(1,700f,300f,0); move(1,702f,300f,16)
        }
        val normal=swipe(HudSettings()); val fast=swipe(HudSettings(sensitivity=1200))
        val old=2f*2.2f/(720*.0018f*16)
        assertTrue(normal.state(16).rightX>old)
        assertEquals(1f,fast.state(16).rightX,.0001f)
        assertEquals(0f,fast.state(48).rightX,0f)
        val slow=swipe(HudSettings(lookDeadzone=0))
        assertTrue(normal.state(16).rightX>slow.state(16).rightX)
    }
    @Test fun customCompositeActionsAndGesturesAreIndependentOfOtherHeldControlsAndCancelClearsEverything() {
        val setting=HudSettings(placements=mapOf("pass" to HudPlacement(.75f,.80f,
            action=HudAction(HudBinding.RT,HudBinding.A),gestures=mapOf(HudSwipe.UP to HudGesture("Combo",HudAction(HudBinding.RB,HudBinding.B))))))
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC,setting)
        val pass=input.controls.single { it.id=="pass" }; val sprint=input.controls.single { it.id=="sprint" }
        input.down(1,pass.x,pass.y,0); input.down(2,sprint.x,sprint.y,0)
        assertEquals(1f,input.state(0).rightTrigger,0f)
        input.move(1,pass.x,pass.y-40,16)
        assertEquals(8224,input.state(16).buttons)
        input.up(1,20)
        assertEquals(1f,input.state(100).rightTrigger,0f)
        assertEquals(0,input.state(100).buttons)
        input.clear(); assertEquals(GamepadState(),input.state(101))
    }
    @Test fun gameFamiliesChooseTheirHudWithoutDependingOnTranslatedCatalogIds() {
        val cases=mapOf("Fortnite" to HudPreset.FORTNITE,"Call of Duty®: Black Ops 7" to HudPreset.CALL_OF_DUTY,
            "Forza Horizon 6 Premium Edition" to HudPreset.FORZA,"Forza Motorsport" to HudPreset.FORZA,
            "EA SPORTS FC™ 26" to HudPreset.EA_FC,"FIFA 19" to HudPreset.EA_FC,
            "Hades II" to HudPreset.HADES,"Hades 2" to HudPreset.HADES,"Hades" to HudPreset.HADES,
            "F1 25" to HudPreset.RACING,"Halo Infinite" to HudPreset.SHOOTER,"Unknown Adventure" to HudPreset.GAMEPAD)
        cases.forEach { (name,preset) -> assertEquals(name,preset,GameHud.automatic("opaque-cloud-id",name)) }
        assertEquals(HudPreset.FORTNITE,GameHud.automatic("FORTNITE","Nome indisponível"))
    }
    @Test fun everyLayoutFitsPhoneAndTabletBoundsAndHasUniqueEditableControls() {
        for(preset in HudPreset.entries) for((width,height) in listOf(1280 to 590,2400 to 1080,1024 to 768)) {
            val controls=VirtualGamepad(width,height,preset).controls
            assertEquals(controls.size,controls.map { it.id }.distinct().size)
            assertTrue(controls.all { it.x-it.radius>=0 && it.y-it.radius>=0 && it.x+it.radius<=width && it.y+it.radius<=height })
            assertTrue(controls.any { it.binding==HudBinding.A }); assertTrue(controls.any { it.binding==HudBinding.B })
            assertTrue(controls.any { it.binding==HudBinding.MENU }); assertTrue(controls.any { it.binding==HudBinding.VIEW })
        }
    }
    @Test fun racingSteeringAndPedalsCanBeHeldIndependently() {
        val input=VirtualGamepad(1280,720,HudPreset.FORZA)
        fun down(id: Int,name: String) { input.controls.single { it.id==name }.let { input.down(id,it.x,it.y,0) } }
        down(1,"steer-left"); down(2,"accelerate"); down(3,"brake")
        assertEquals(-1f,input.state(0).leftX,0f)
        assertEquals(1f,input.state(0).leftTrigger,0f); assertEquals(1f,input.state(0).rightTrigger,0f)
        down(4,"steer-right"); assertEquals(0f,input.state(0).leftX,0f)
        input.up(3); assertEquals(0f,input.state(0).leftTrigger,0f); assertEquals(1f,input.state(0).rightTrigger,0f)
        input.up(4); assertEquals(-1f,input.state(0).leftX,0f)
        input.clear(); assertEquals(GamepadState(),input.state(0))
    }
    @Test fun twoFireButtonsDoNotReleaseEachOtherAndDraggingFireAlsoMovesTheCamera() {
        val input=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY)
        val left=input.controls.single { it.id=="fire-left" }; val right=input.controls.single { it.id=="fire" }
        input.down(1,left.x,left.y,0); input.down(2,right.x,right.y,0)
        input.move(2,right.x+12,right.y-6,16)
        val aimed=input.state(16)
        assertEquals(1f,aimed.rightTrigger,0f); assertTrue(aimed.rightX>0); assertTrue(aimed.rightY<0)
        input.up(1); assertEquals(1f,input.state(20).rightTrigger,0f)
        input.up(2); assertEquals(GamepadState(),input.state(21))
    }
    @Test fun swipeCameraStopsWhenTheFingerStopsAndHasConsistentSensitivityAcrossEventRates() {
        fun swipe(dt: Long,dx: Float): VirtualGamepad = VirtualGamepad(1280,720,HudPreset.FORTNITE).apply {
            down(1,700f,300f,0); move(1,700f+dx,300f,dt)
        }
        val slow=swipe(16,8f); val fast=swipe(8,4f)
        assertEquals(slow.state(16).rightX,fast.state(8).rightX,.0001f)
        assertTrue(slow.state(16).rightX>0)
        assertNotNull(slow.nextLookReset(16))
        assertEquals(0f,slow.state(97).rightX,0f)
        assertNull(slow.nextLookReset(97))
    }
    @Test fun fortniteConstructionKeepsTheMovementFingerAndUsesBuilderProButtons() {
        val input=VirtualGamepad(1280,720,HudPreset.FORTNITE)
        val move=input.controls.single { it.id=="move" }
        input.down(1,move.x,move.y,0); input.move(1,move.x+move.radius,move.y,10)
        val build=input.controls.single { it.id=="build-toggle" }
        input.down(2,build.x,build.y,10); assertEquals(32,input.state(10).buttons)
        input.up(2)
        assertEquals(HudPreset.FORTNITE_BUILD,input.preset)
        assertEquals(1f,input.state(11).leftX,.0001f)
        assertEquals(0,input.state(11).buttons)
        val wall=input.controls.single { it.id=="wall" }; input.down(3,wall.x,wall.y,12)
        assertEquals(1f,input.state(12).rightTrigger,0f)
        input.up(3)
        assertEquals(HudBinding.LT,input.controls.single { it.id=="stairs" }.binding)
        assertEquals(HudBinding.RB,input.controls.single { it.id=="floor" }.binding)
        assertEquals(HudBinding.LB,input.controls.single { it.id=="roof" }.binding)
    }
    @Test fun savedButtonRemapsChangeTheActualControllerReport() {
        val layout=HudSettings(placements=mapOf("pass" to HudPlacement(.62f,.80f,1.2f,HudBinding.X)))
        val input=VirtualGamepad(1280,720,HudPreset.EA_FC,layout)
        val pass=input.controls.single { it.id=="pass" }
        input.down(1,pass.x,pass.y,0)
        assertEquals(64,input.state(0).buttons)
        val bytes=java.nio.ByteBuffer.wrap(GamepadPacket.encode(input.state(0),1,0.0)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(64,bytes.getShort(16).toInt())
    }
}
