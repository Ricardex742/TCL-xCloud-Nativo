package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.ui.GameTouchView
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class TouchUiTest {
    @Test fun androidPointerZeroAnd31BecomeIndependentPositiveContactsThroughMoveAndCancel() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val received=mutableListOf<NativePointer>()
        val view=GameTouchView(controller.get(),{}, {received.addAll(it)}, {x,y -> x/1280 to y/720},{})
        controller.get().setContentView(view); view.configure(TouchMode.NATIVE); layout(view)
        fun event(action: Int,ids: List<Int>) {
            val props=ids.map { MotionEvent.PointerProperties().apply { id=it; toolType=MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val points=ids.map { MotionEvent.PointerCoords().apply { x=300f+it; y=400f; pressure=1f } }.toTypedArray()
            MotionEvent.obtain(0,10,action,ids.size,props,points,0,0,1f,1f,0,0,0,0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
        event(MotionEvent.ACTION_DOWN,listOf(0))
        event(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),listOf(0,31))
        event(MotionEvent.ACTION_MOVE,listOf(0,31))
        event(MotionEvent.ACTION_POINTER_UP or (1 shl 8),listOf(0,31))
        event(MotionEvent.ACTION_CANCEL,listOf(0))
        assertEquals(listOf(1,32,1,32,32,1),received.map { it.id })
        assertEquals(listOf(1,1,3,3,2,2),received.map { it.type })
        controller.pause().stop().destroy()
    }
    private fun layout(view: View) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1280,720)
            if(it==0) shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun pointer(view: View,type: Int,x: Float,y: Float) {
        MotionEvent.obtain(0,10,type,x,y,0).also { assertTrue(view.dispatchTouchEvent(it)); it.recycle() }
    }
    @Test fun virtualOverlaySendsButtonsAndReleasesOnFocusLossWithoutAWebView() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var state=GamepadState()
        val view=GameTouchView(controller.get(),{ state=it },{ fail("Virtual input sent native contacts") },{ x,y -> x/1280 to y/720 },{})
        controller.get().setContentView(view); view.configure(TouchMode.VIRTUAL); layout(view)
        val a=VirtualGamepad(1280,720).controls.single { it.label=="A" }
        pointer(view,MotionEvent.ACTION_DOWN,a.x,a.y)
        assertEquals(16,state.buttons)
        val image=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File("build/reports/dashboard/green-touch-controller.png").apply { parentFile?.mkdirs() }.outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        view.releaseInputs(); assertEquals(GamepadState(),state)
        controller.pause().stop().destroy()
    }
    @Test fun nativeOverlayReleasesAFingerEvenWhenItLeavesTheVideoAndCancelsAllContacts() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val events=mutableListOf<NativePointer>()
        val view=GameTouchView(controller.get(),{}, { events.addAll(it) },{ x,y -> if(x<100) null else x/1280 to y/720 },{})
        controller.get().setContentView(view); view.configure(TouchMode.NATIVE); layout(view)
        pointer(view,MotionEvent.ACTION_DOWN,600f,400f)
        pointer(view,MotionEvent.ACTION_UP,20f,400f)
        assertEquals(listOf(1,2),events.map { it.type })
        assertEquals(events[0].x,events[1].x,0f)
        pointer(view,MotionEvent.ACTION_DOWN,800f,500f)
        pointer(view,MotionEvent.ACTION_CANCEL,800f,500f)
        assertEquals(listOf(1,2,1,2),events.map { it.type })
        controller.pause().stop().destroy()
    }
    @Test fun phoneWithoutPressureSensorStillSendsAnActiveNativeFinger() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val events=mutableListOf<NativePointer>()
        val view=GameTouchView(controller.get(),{}, { events.addAll(it) },{ x,y -> x/1280 to y/720 },{})
        controller.get().setContentView(view); view.configure(TouchMode.NATIVE); layout(view)
        val properties=arrayOf(MotionEvent.PointerProperties().apply { id=3; toolType=MotionEvent.TOOL_TYPE_FINGER })
        val positions=arrayOf(MotionEvent.PointerCoords().apply { x=600f; y=400f; pressure=0f; touchMajor=20f })
        MotionEvent.obtain(0,10,MotionEvent.ACTION_DOWN,1,properties,positions,0,0,1f,1f,0,0,0,0).also {
            assertTrue(view.dispatchTouchEvent(it)); it.recycle()
        }
        assertEquals(1f,events.single().pressure,0f)
        assertTrue(events.single().width>0)
        view.releaseInputs()
        assertEquals(listOf(1,2),events.map { it.type })
        assertEquals(0f,events.last().pressure,0f)
        controller.pause().stop().destroy()
    }
}
