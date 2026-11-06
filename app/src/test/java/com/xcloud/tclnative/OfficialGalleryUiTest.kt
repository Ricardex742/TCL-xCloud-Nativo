package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfficialGalleryUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(view: View,w: Int=1280,h: Int=720) {
        repeat(3) { shadowOf(Looper.getMainLooper()).idle(); view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h) }
    }
    private fun source(name: String,kind: OfficialGalleryKind)=OfficialGallery.parse(kind,JSONArray(javaClass.getResource("/catalog/$name-br-2026-11-06.json")!!.readText()))

    @Test fun everyOfficialProductIsBrowsableWithoutInventingALaunchAndErrorsAreNotEmptySuccess() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        var selected: GalleryProduct?=null;var launched: GameTitle?=null
        val view=GameDashboardView(activity,GameLibrary(activity,"official-catalog"),{launched=it},{},{},{},onPlayProduct={selected=it})
        try {
            activity.setContentView(view)
            view.setGames(emptyList())
            view.setOfficialGallery(OfficialGalleryKind.GAME_PASS,source("gamepass",OfficialGalleryKind.GAME_PASS))
            view.selectTab(LibraryTab.GAME_PASS);layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            assertEquals(560,grid.adapter!!.itemCount)
            assertNull(view.selectedGame)
            grid.getChildAt(0).performClick()
            assertNotNull(selected);assertNull(launched)
            val binding=GameTitle("server-validated-id",selected!!.productId,supportedInputTypes=setOf("NativeTouch"))
            view.bindGalleryProduct(selected!!.productId,binding);layout(view)
            grid.getChildAt(0).performClick()
            assertEquals(binding.titleId,launched!!.titleId)
            assertEquals(setOf("NativeTouch"),launched!!.supportedInputTypes)
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,source("purchased",OfficialGalleryKind.PURCHASED))
            view.selectTab(LibraryTab.PURCHASED);layout(view)
            assertEquals(3781,grid.adapter!!.itemCount)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text.contains("Não confirma a propriedade") })
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,null);layout(view)
            assertEquals(0,grid.adapter!!.itemCount)
            assertTrue(all(view).filterIsInstance<TextView>().any { it.text=="LISTA OFICIAL INDISPONÍVEL" })
        } finally {view.close();controller.pause().stop().destroy()}
    }

    @Test fun globalSearchIncludesUnboundProductsAndTheNewTabFitsAndCyclesByController() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val view=GameDashboardView(activity,GameLibrary(activity,"official-search"),{},{},{},{})
        try {
            activity.setContentView(view)
            val product=GalleryProduct("BBBBBBBBBBBB","Produto comprado de demonstração")
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,OfficialGallery(OfficialGalleryKind.PURCHASED,listOf(product),true))
            view.selectTab(LibraryTab.PURCHASED)
            view.setSearch("comprado")
            assertEquals(LibraryTab.ALL,view.activeTab)
            layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            assertEquals(1,grid.adapter!!.itemCount)
            view.setSearch("");view.selectTab(LibraryTab.PURCHASED)
            for((w,h) in listOf(1280 to 720,2260 to 1080,3840 to 2160)) {
                layout(view,w,h)
                val tab=all(view).filterIsInstance<TextView>().single { it.contentDescription?.startsWith(LibraryTab.PURCHASED.label)==true }
                assertEquals(1,tab.layout.lineCount)
                assertTrue((0 until tab.layout.lineCount).all { tab.layout.getEllipsisCount(it)==0 })
                assertTrue(tab.left>=0 && tab.right<=(tab.parent as View).width)
            }
            layout(view)
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap));val out=File("build/reports/dashboard/official-purchased-demo.png");out.parentFile.mkdirs()
            out.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_R1))
            assertEquals(LibraryTab.ADDED,view.activeTab)
            view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_L1))
            assertEquals(LibraryTab.PURCHASED,view.activeTab)
        } finally {view.close();controller.pause().stop().destroy()}
    }
}
