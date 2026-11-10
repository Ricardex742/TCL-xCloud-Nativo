package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class MobileHudResponseUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(v: View,height: Int=720) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,1280,height)
    }
    private fun choose(text: String) {
        val d=ShadowDialog.getLatestDialog() as ConsoleDialog
        all(d.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick()
    }
    private fun event(v: GameTouchView,type: Int,time: Long,x: Float,y: Float) {
        val e=MotionEvent.obtain(time,time,type,x,y,0)
        try { v.onTouchEvent(e) } finally { e.recycle() }
    }
    private fun save(view: View,name: String) {
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(b))
        File("build/reports/huds/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle()
    }
    @Test fun codOptionsPersistAndTheEnabledFireButtonSendsBothTriggers() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        var state=GamepadState();val store=GameHudStore(a);val game=GameTitle("CODMWIII","Call of Duty: Modern Warfare III")
        val view=GameTouchView(a,{state=it},{fail("Virtual HUD sent pointers")},{_,_ -> null},{})
        try {
            a.setContentView(view);layout(a.window.decorView,1440);layout(view)
            view.configure(TouchMode.VIRTUAL,game,store.read(game.titleId))
            assertFalse(store.read(game.titleId).aimAndFire)
            val panel=GameHudPanel(a,game,store,{view.applyHud(it)},{},{},{})
            panel.show();choose("Mirar ao atirar:");choose("Ativado — mirar e atirar")
            assertTrue(GameHudStore(a).read(game.titleId).aimAndFire)
            assertTrue(view.hudDiagnostics().contains("mirar ao atirar ativado"))
            val settings=ShadowDialog.getLatestDialog() as ConsoleDialog
            repeat(2) { shadowOf(Looper.getMainLooper()).idle();settings.panel.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1296,View.MeasureSpec.AT_MOST));settings.panel.layout(0,0,settings.panel.measuredWidth,settings.panel.measuredHeight) }
            settings.panel.scrollTo(0,0);save(settings.panel,"cod-mobile-settings");settings.dismiss()
            layout(view);view.setBackgroundColor(0xff2b3139.toInt());save(view,"cod-mobile")
            val fire=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY,store.read(game.titleId)).controls.single { it.id=="fire" }
            val now=SystemClock.uptimeMillis()
            event(view,MotionEvent.ACTION_DOWN,now,fire.x,fire.y)
            assertEquals(1f,state.leftTrigger,0f);assertEquals(1f,state.rightTrigger,0f)
            event(view,MotionEvent.ACTION_CANCEL,now+1,fire.x,fire.y)
            assertEquals(GamepadState(),state)
            shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS);assertEquals(GamepadState(),state)
            panel.show();choose("Arrastar analógico para correr:");choose("Desativado")
            assertFalse(GameHudStore(a).read(game.titleId).sprintGesture)
            assertEquals(HudSettings(),store.read("ANOTHER-GAME"))
        } finally { view.releaseInputs();c.pause().stop().destroy() }
    }
    @Test fun selectingAlternateRestoresTheThroughPassCommandWithoutMovingIt() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get()
        var state=GamepadState();val game=GameTitle("EAFC26","EA SPORTS FC 26");val store=GameHudStore(a)
        val view=GameTouchView(a,{state=it},{},{_,_ -> null},{})
        try {
            a.setContentView(view);layout(a.window.decorView);layout(view)
            val position=HudPlacement(.74f,.53f,1.1f,action=HudAction(HudBinding.RT))
            store.save(game.titleId,HudSettings(footballScheme=FootballScheme.ALTERNATE,placements=mapOf("through" to position)))
            view.configure(TouchMode.VIRTUAL,game,store.read(game.titleId))
            GameHudPanel(a,game,store,{view.applyHud(it)},{},{},{}).show()
            choose("Esquema de futebol:");choose("Alternativo")
            val restored=GameHudStore(a).read(game.titleId)
            assertEquals(FootballScheme.ALTERNATE,restored.footballScheme)
            assertEquals(position.x,restored.placements.getValue("through").x,0f)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            GameHudPanel(a,game,store,{view.applyHud(it)},{},{},{}).show();choose("Esquema de futebol:")
            val scheme=ShadowDialog.getLatestDialog() as ConsoleDialog
            repeat(2) { shadowOf(Looper.getMainLooper()).idle();scheme.panel.measure(View.MeasureSpec.makeMeasureSpec(1050,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST));scheme.panel.layout(0,0,scheme.panel.measuredWidth,scheme.panel.measuredHeight) }
            assertTrue(all(scheme.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("Alternativo") }.hasFocus())
            scheme.panel.scrollTo(0,0);save(scheme.panel,"football-scheme-alternate");scheme.dismiss()
            layout(view);view.setBackgroundColor(0xff2b3139.toInt());save(view,"ea-fc-alternate")
            val through=VirtualGamepad(1280,720,HudPreset.EA_FC,restored).controls.single { it.id=="through" }
            val now=SystemClock.uptimeMillis();view.beginSession()
            event(view,MotionEvent.ACTION_DOWN,now,through.x,through.y)
            assertEquals(128,state.buttons);assertEquals(0f,state.rightTrigger,0f)
            event(view,MotionEvent.ACTION_UP,now+1,through.x,through.y)
            shadowOf(Looper.getMainLooper()).idleFor(65,TimeUnit.MILLISECONDS)
            assertEquals(GamepadState(),state)
            assertTrue(view.hudDiagnostics().contains("Enfiada [through] → Y"))
            assertFalse(view.hudDiagnostics().contains("corrida por arrasto"))
            assertFalse(view.hudDiagnostics().contains("mirar ao atirar"))
            GameHudPanel(a,game,store,{view.applyHud(it)},{},{},{}).show();choose("Restaurar HUD deste jogo")
            assertEquals(FootballScheme.ALTERNATE,GameHudStore(a).read(game.titleId).footballScheme)
        } finally { view.releaseInputs();c.pause().stop().destroy() }
    }
    @Test fun oldAimEventsRemainResponsiveAndTheDiagnosticsIncludeHudTouchAge() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();var state=GamepadState()
        val view=GameTouchView(a,{state=it},{},{_,_ -> null},{})
        try {
            a.setContentView(view);layout(view);view.configure(TouchMode.VIRTUAL,GameTitle("COD","Call of Duty"));view.beginSession()
            val aim=VirtualGamepad(1280,720,HudPreset.CALL_OF_DUTY).controls.single { it.id=="aim" }
            val stamp=SystemClock.uptimeMillis();shadowOf(Looper.getMainLooper()).idleFor(150,TimeUnit.MILLISECONDS)
            event(view,MotionEvent.ACTION_DOWN,stamp,aim.x,aim.y)
            event(view,MotionEvent.ACTION_MOVE,stamp+16,aim.x+5,aim.y-2)
            assertTrue(state.rightX>0);assertEquals(1f,state.leftTrigger,0f)
            assertTrue(view.hudDiagnostics().contains("Mirar [aim] → LT"))
            assertFalse(view.nativeDiagnostics().contains("idade média/pico n/d"))
            shadowOf(Looper.getMainLooper()).idleFor(33,TimeUnit.MILLISECONDS)
            assertEquals(0f,state.rightX,0f);assertEquals(1f,state.leftTrigger,0f)
            event(view,MotionEvent.ACTION_UP,stamp+20,aim.x+5,aim.y-2);assertEquals(GamepadState(),state)
            view.beginSession();assertTrue(view.nativeDiagnostics().contains("idade média/pico n/d"))
        } finally { view.releaseInputs();c.pause().stop().destroy() }
    }
    @Test fun batchedCameraSamplesPublishOnlyOneCurrentControllerUpdate() {
        val c=Robolectric.buildActivity(Activity::class.java).setup();val a=c.get();val states=mutableListOf<GamepadState>()
        val view=GameTouchView(a,{states.add(it)},{},{_,_ -> null},{})
        try {
            a.setContentView(view);layout(view);view.configure(TouchMode.VIRTUAL,GameTitle("COD","Call of Duty"));states.clear()
            val now=SystemClock.uptimeMillis()
            event(view,MotionEvent.ACTION_DOWN,now,700f,350f)
            val move=MotionEvent.obtain(now,now+16,MotionEvent.ACTION_MOVE,704f,350f,0)
            move.addBatch(now+32,708f,350f,1f,1f,0);move.addBatch(now+48,712f,350f,1f,1f,0)
            try { view.onTouchEvent(move) } finally { move.recycle() }
            assertEquals(2,states.size);assertTrue(states.last().rightX>0)
        } finally { view.releaseInputs();c.pause().stop().destroy() }
    }
}
