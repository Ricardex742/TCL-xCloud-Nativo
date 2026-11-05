package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogUiWorkTest {
    @Test fun fixedCoverChangesInvalidatePixelsWithoutRequestingAParentRemeasureAndCanStillResize() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val parent=object: FrameLayout(a) { var requests=0; override fun requestLayout() { requests++; super.requestLayout() } }
        val image=FixedArtworkView(a)
        parent.addView(image,FrameLayout.LayoutParams(192,320)); a.setContentView(parent)
        fun layout() {
            parent.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            parent.layout(0,0,1280,720)
        }
        layout(); val before=parent.requests
        val bitmap=Bitmap.createBitmap(128,192,Bitmap.Config.RGB_565).apply { eraseColor(Color.BLUE) }
        image.setImageBitmap(bitmap); image.setImageDrawable(null); image.setImageBitmap(bitmap)
        assertEquals(before,parent.requests)
        assertEquals(192,image.width); assertEquals(320,image.height)
        val pixels=Bitmap.createBitmap(192,320,Bitmap.Config.ARGB_8888)
        image.draw(Canvas(pixels))
        assertEquals(Color.BLUE,pixels.getPixel(96,160))
        image.layoutParams=FrameLayout.LayoutParams(256,384); layout()
        assertEquals(256,image.width); assertEquals(384,image.height)
        c.pause().stop().destroy()
    }
    @Test fun cachedAndNewImagesUseTheSameFrameBudgetAndCancelledBindingsDoNotRun() {
        val frames=mutableListOf<()->Unit>(); val delivered=mutableListOf<Int>()
        val queue=ArtworkFrameQueue({frames.add(it)},2)
        repeat(9) { i -> queue.post(i) { delivered.add(i) } }
        queue.remove(0); queue.post(1) { delivered.add(100) }
        assertTrue(delivered.isEmpty()); assertEquals(1,frames.size)
        frames.removeAt(0).invoke()
        assertEquals(listOf(100,2),delivered)
        assertEquals(1,frames.size)
        queue.clear(); frames.removeAt(0).invoke()
        assertEquals(listOf(100,2),delivered)
    }
    @Test fun metadataBurstsAreCoalescedWithoutLosingLatestTitlesOrSelection() {
        val c=Robolectric.buildActivity(Activity::class.java).setup(); val a=c.get()
        val view=GameDashboardView(a,com.xcloud.tclnative.catalog.GameLibrary(a,"coalescing"),{},{},{},{})
        a.setContentView(view)
        val initial=listOf(com.xcloud.tclnative.signaling.GameTitle("one","One"))
        view.setGames(initial)
        repeat(20) { view.updateMetadata(listOf(initial[0].copy(name="Name $it"))) }
        assertEquals("One",view.selectedGame!!.name)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("Name 19",view.selectedGame!!.name)
        view.updateMetadata(listOf(initial[0].copy(name="Old"))); view.setGames(initial)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("One",view.selectedGame!!.name)
        view.close(); c.pause().stop().destroy()
    }
}
