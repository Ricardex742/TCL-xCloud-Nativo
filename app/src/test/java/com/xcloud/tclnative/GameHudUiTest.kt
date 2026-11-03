package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class GameHudUiTest {
    private fun layout(view: View,width: Int=1280,height: Int=720) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            view.layout(0,0,width,height)
            if(it==0) shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun event(view: View,action: Int,time: Long,vararg fingers: Triple<Int,Float,Float>) {
        val properties=fingers.map { MotionEvent.PointerProperties().apply { id=it.first; toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val points=fingers.map { MotionEvent.PointerCoords().apply { x=it.second; y=it.third; pressure=1f } }.toTypedArray()
        MotionEvent.obtain(0,time,action,fingers.size,properties,points,0,0,1f,1f,0,0,0x1002,0).also {
            assertTrue(view.dispatchTouchEvent(it)); it.recycle()
        }
    }
    private fun tap(view: View,x: Float,y: Float) {
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,x,y))
        event(view,MotionEvent.ACTION_UP,now+1,Triple(0,x,y))
    }
    private fun save(view: View,name: String) {
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File("build/reports/huds/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    @Test fun threeFingersMoveAimShootAndJumpThroughTheGamepadWireFormatWithoutNativePointers() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{state=it},{fail("The virtual HUD sent native touch")},{x,y -> x/1280 to y/720},{})
        controller.get().setContentView(view)
        view.configure(TouchMode.VIRTUAL,GameTitle("FORTNITE","Fortnite")); layout(view)
        val controls=VirtualGamepad(1280,720,HudPreset.FORTNITE).controls
        val move=controls.single { it.id=="move" }; val fire=controls.single { it.id=="fire" }; val jump=controls.single { it.id=="jump" }
        val left=Triple(3,move.x+move.radius,move.y)
        val shooting=Triple(7,fire.x,fire.y)
        val jumping=Triple(11,jump.x,jump.y)
        event(view,MotionEvent.ACTION_DOWN,0,Triple(3,move.x,move.y))
        event(view,MotionEvent.ACTION_MOVE,16,left)
        event(view,MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),16,left,shooting)
        event(view,MotionEvent.ACTION_POINTER_DOWN or (2 shl 8),16,left,shooting,jumping)
        event(view,MotionEvent.ACTION_MOVE,32,left,shooting.copy(second=fire.x+12),jumping)
        assertEquals(1f,state.leftX,.0001f); assertEquals(16,state.buttons)
        assertEquals(1f,state.rightTrigger,0f); assertTrue(state.rightX>0)
        val bytes=java.nio.ByteBuffer.wrap(GamepadPacket.encode(state,1,0.0)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(16,bytes.getShort(16).toInt()); assertEquals(65535,bytes.getShort(28).toInt() and 65535)
        event(view,MotionEvent.ACTION_POINTER_UP or (2 shl 8),40,left,shooting,jumping)
        assertEquals(0,state.buttons); assertEquals(1f,state.rightTrigger,0f)
        event(view,MotionEvent.ACTION_POINTER_UP or (1 shl 8),48,left,shooting)
        assertEquals(0f,state.rightTrigger,0f); assertEquals(1f,state.leftX,.0001f)
        event(view,MotionEvent.ACTION_CANCEL,50,left)
        assertEquals(GamepadState(),state)
        controller.pause().stop().destroy()
    }
    @Test fun cameraAutomaticallyReturnsToNeutralWhenTheFingerStopsMoving() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{state=it},{},{_,_ -> null},{})
        controller.get().setContentView(view); view.configure(TouchMode.VIRTUAL,GameTitle("FORTNITE","Fortnite")); layout(view)
        val now=SystemClock.uptimeMillis()
        event(view,MotionEvent.ACTION_DOWN,now,Triple(0,700f,300f))
        event(view,MotionEvent.ACTION_MOVE,now+16,Triple(0,712f,300f))
        assertTrue(state.rightX>0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
        assertEquals(0f,state.rightX,0f)
        view.releaseInputs(); controller.pause().stop().destroy()
    }
    @Test fun editorSavesPositionsAndBindingsPerGameAndCancelNeverSendsAnAction() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); val store=GameHudStore(activity)
        var state=GamepadState(); var saves=0
        val view=GameTouchView(activity,{state=it},{},{_,_ -> null},{})
        view.setBackgroundColor(Color.rgb(24,43,33))
        activity.setContentView(view)
        val game=GameTitle("EAFC26","EA SPORTS FC 26")
        view.configure(TouchMode.VIRTUAL,game,store.read(game.titleId)); layout(view)
        view.onHudSaved={ store.save(game.titleId,it); saves++; true }
        view.beginEditing()
        val pass=VirtualGamepad(1280,720,HudPreset.EA_FC).controls.single { it.id=="pass" }
        event(view,MotionEvent.ACTION_DOWN,0,Triple(0,pass.x,pass.y))
        event(view,MotionEvent.ACTION_MOVE,16,Triple(0,1280*.67f,720*.77f))
        event(view,MotionEvent.ACTION_UP,32,Triple(0,1280*.67f,720*.77f))
        assertEquals(GamepadState(),state)
        view.setEditedBinding("pass",HudBinding.X)
        save(view,"editor")
        tap(view,1280*.635f,720*.07f)
        assertFalse(view.isEditing); assertEquals(1,saves)
        val saved=GameHudStore(activity).read("EAFC26")
        assertEquals(.67f,saved.placements["pass"]!!.x,.0001f)
        assertEquals(HudBinding.X,saved.placements["pass"]!!.binding)
        event(view,MotionEvent.ACTION_DOWN,50,Triple(0,1280*.67f,720*.77f))
        assertEquals(64,state.buttons)
        view.releaseInputs(); view.beginEditing()
        event(view,MotionEvent.ACTION_DOWN,60,Triple(0,1280*.67f,720*.77f))
        event(view,MotionEvent.ACTION_MOVE,76,Triple(0,1280*.55f,720*.8f))
        view.cancelEditing()
        assertEquals(saved,view.configuration); assertEquals(1,saves)
        assertEquals(GamepadState(),state)
        assertEquals(HudSettings(),store.read("FORTNITE"))
        controller.pause().stop().destroy()
    }
    @Test fun inGameSettingsChooseAndPersistTheHudWithoutSendingGameActions() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView)
        val store=GameHudStore(activity); val game=GameTitle("FORTNITE","Fortnite")
        var applied=HudSettings(); var edits=0; var hidden=0; var page=HudPreset.FORTNITE
        val panel=GameHudPanel(activity,game,store,{ applied=it },{ edits++ },{ hidden++ },{ page=it })
        fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
            (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun choose(text: String) {
            val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
            all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(text) }.performClick()
        }
        val settings=panel.show()
        val root=settings.window!!.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(settings.window!!.attributes.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        root.layout(0,0,root.measuredWidth,root.measuredHeight)
        save(root,"settings")
        choose("Opacidade:"); choose("75%")
        assertEquals(75,applied.opacity); assertEquals(applied,GameHudStore(activity).read(game.titleId))
        choose("Mostrar HUD de construção"); assertEquals(HudPreset.FORTNITE_BUILD,page)
        panel.show(); choose("Editar posição"); assertEquals(1,edits)
        panel.show(); choose("Layout:"); choose("Corrida — padrão")
        assertEquals(HudPreset.RACING,applied.preset)
        assertEquals(HudSettings(),store.read("HADES2"))
        choose("Ocultar HUD"); assertEquals(1,hidden)
        controller.pause().stop().destroy()
    }
    @Test fun actionHudsRenderAtPhoneSizesAndPageChangesKeepTheSavedProfile() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val root=FrameLayout(activity).apply { setBackgroundColor(Color.rgb(24,43,33)) }
        val view=GameTouchView(activity,{}, {},{_,_ -> null},{})
        root.addView(view,FrameLayout.LayoutParams(-1,-1)); activity.setContentView(root)
        val profiles=mapOf("fortnite" to HudPreset.FORTNITE,"fortnite-build" to HudPreset.FORTNITE_BUILD,
            "call-of-duty" to HudPreset.CALL_OF_DUTY,"forza" to HudPreset.FORZA,"ea-fc" to HudPreset.EA_FC,"hades" to HudPreset.HADES)
        for((name,preset) in profiles) {
            view.configure(TouchMode.VIRTUAL,GameTitle("DEMO","Demonstração"),HudSettings(preset=preset))
            layout(root); save(root,name)
            layout(root,2400,1080)
            assertEquals(preset,view.preset)
        }
        view.configure(TouchMode.VIRTUAL,GameTitle("FORTNITE","Fortnite"),HudSettings())
        view.showPage(HudPreset.FORTNITE_BUILD)
        view.applyHud(view.configuration.copy(opacity=75))
        assertEquals(HudPreset.FORTNITE_BUILD,view.preset)
        controller.pause().stop().destroy()
    }
}
