package com.xcloud.tclnative

import android.app.Activity
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.renderer.AiPqSurfaceRenderer
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.GameTouchView
import com.xcloud.tclnative.ui.MainActivity
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.NativePointer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29],qualifiers="w1452dp-h689dp-land-mdpi")
class LiveAspectUiTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun choose(text: String) {
        val dialog=ShadowDialog.getLatestDialog() as ConsoleDialog
        all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(text) }.performClick()
    }
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1452,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(689,View.MeasureSpec.EXACTLY))
        view.layout(0,0,1452,689)
    }
    @Test fun gameMenuAppliesStretchAndZoomWithoutReconnectingAndReleasesContactsInTheOldSpace() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val context=controller.get()
        context.setContentView(FrameLayout(context))
        val renderer=AiPqSurfaceRenderer(context)
        val pointers=mutableListOf<NativePointer>()
        val touch=GameTouchView(context,{}, {pointers.addAll(it)},renderer::touchCoordinates,{})
        // Invoke the Activity's production settings callback without starting auth or JNI/WebRTC.
        val activity=MainActivity()
        ReflectionHelpers.setField(activity,"surfaceRenderer",renderer)
        ReflectionHelpers.setField(activity,"touchView",touch)
        val apply=MainActivity::class.java.getDeclaredMethod("applyPlaybackSettings",PlaybackSettings::class.java).apply { isAccessible=true }
        val store=PlaybackSettingsStore(context)
        store.save(PlaybackSettings(aspect=VideoAspect.ORIGINAL))
        renderer.aspect=VideoAspect.ORIGINAL
        ReflectionHelpers.setField(renderer,"videoWidth",1280)
        ReflectionHelpers.setField(renderer,"videoHeight",720)
        layout(renderer);layout(touch);touch.configure(TouchMode.NATIVE)
        fun down() { MotionEvent.obtain(0,10,MotionEvent.ACTION_DOWN,400f,300f,0).also { touch.dispatchTouchEvent(it);it.recycle() } }
        var changes=0;var menus=0
        val panel=PlaybackSettingsPanel(context,store,{changes++;apply.invoke(activity,it)},{},{},{})
        fun gameMenu() {
            menus++
            GameMenuPanel.show(context,{}, {}, {}, {},aspect={panel.chooseAspect(onBack=::gameMenu)})
        }
        try {
            assertNull(renderer.touchCoordinates(0f,300f))
            down();assertEquals(listOf(1),pointers.map { it.type })
            gameMenu();choose("Proporção da imagem")
            assertEquals(0,changes);assertEquals(VideoAspect.ORIGINAL,renderer.aspect)
            choose("Esticar —")
            assertEquals(1,changes);assertEquals(2,menus)
            assertEquals(VideoAspect.STRETCH,renderer.aspect)
            assertEquals(VideoAspect.STRETCH,PlaybackSettingsStore(context).read().aspect)
            assertEquals(listOf(1,2),pointers.map { it.type })
            assertEquals(pointers.first().x,pointers.last().x,0f)
            assertEquals(pointers.first().y,pointers.last().y,0f)
            assertEquals(0f,renderer.touchCoordinates(0f,0f)!!.first,0f)
            assertEquals(1f,renderer.touchCoordinates(1452f,689f)!!.second,0f)
            down();val contacts=pointers.size
            apply.invoke(activity,store.read().copy(brightness=25))
            assertEquals(contacts,pointers.size) // Image filters must not release an unchanged touch space.
            choose("Proporção da imagem")
            (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,changes);assertEquals(3,menus)
            assertEquals(VideoAspect.STRETCH,renderer.aspect)
            choose("Proporção da imagem");choose("Zoom —")
            assertEquals(2,changes);assertEquals(VideoAspect.ZOOM,renderer.aspect)
            val top=renderer.touchCoordinates(0f,0f)!!
            assertEquals(0f,top.first,0f)
            assertTrue(top.second in .07f.. .09f)
            assertEquals(.5f,renderer.touchCoordinates(726f,344.5f)!!.second,.00001f)
        } finally {
            (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss()
            touch.releaseInputs();renderer.release()
            controller.pause().stop().destroy()
        }
    }
}
