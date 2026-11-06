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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OfficialGalleryUiTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun layout(view: View,w: Int=1280,h: Int=720) {
        (view as? GameDashboardView)?.awaitCatalog()
        repeat(3) { shadowOf(Looper.getMainLooper()).idle(); view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));view.layout(0,0,w,h) }
    }
    private fun source(name: String,kind: OfficialGalleryKind)=OfficialGallery.parse(kind,JSONArray(javaClass.getResource("/catalog/$name-br-2026-11-06.json")!!.readText()))
    private fun awaitStarted(latch: CountDownLatch) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(latch.count>0 && System.nanoTime()<deadline) {
            shadowOf(Looper.getMainLooper()).idle();Thread.sleep(1)
        }
        assertEquals("Catalog processing did not start",0L,latch.count)
    }

    @Test fun largeGalleryWorkLeavesMainFreeAndRapidTabAndSearchChangesOnlyDisplayTheLatestRequest() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val original=source("purchased",OfficialGalleryKind.PURCHASED)
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val first=AtomicBoolean(true)
        val main=Thread.currentThread();val onMain=AtomicBoolean(false)
        val guarded=object: AbstractList<GalleryProduct>() {
            override val size get()=original.products.size
            override fun get(index: Int): GalleryProduct {
                if(Thread.currentThread()===main) onMain.set(true)
                if(first.compareAndSet(true,false)) {
                    entered.countDown()
                    check(release.await(8,TimeUnit.SECONDS)) { "Test did not release catalog worker" }
                }
                return original.products[index]
            }
        }
        var selected: GalleryProduct?=null
        val view=GameDashboardView(activity,GameLibrary(activity,"large-responsive"),{},{},{},{},onPlayProduct={selected=it})
        try {
            activity.setContentView(view)
            val gp=source("gamepass",OfficialGalleryKind.GAME_PASS)
            view.setOfficialGallery(OfficialGalleryKind.GAME_PASS,gp)
            view.selectTab(LibraryTab.GAME_PASS);view.restoreFocus();layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            val oldCover=grid.getChildAt(0)
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,original.copy(products=guarded))
            view.selectTab(LibraryTab.PURCHASED)
            oldCover.performClick()
            assertNull("A cover from the previous category must not start a game",selected)
            awaitStarted(entered)
            // A posted UI event and further input must run while the catalog worker is held.
            var heartbeat=false
            android.os.Handler(Looper.getMainLooper()).post { heartbeat=true }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(heartbeat);assertTrue(view.isCatalogUpdating)
            view.setOfficialGallery(OfficialGalleryKind.GAME_PASS,gp)
            repeat(12) { view.selectTab(LibraryTab.GAME_PASS);view.selectTab(LibraryTab.PURCHASED) }
            val wanted=original.products.last().productId
            view.setSearch(wanted)
            release.countDown();layout(view)
            assertFalse("Gallery traversal ran on Main",onMain.get())
            assertEquals(LibraryTab.ALL,view.activeTab);assertEquals(1,grid.adapter!!.itemCount)
            grid.getChildAt(0).performClick();assertEquals(wanted,selected!!.productId)
            view.setSearch("");view.selectTab(LibraryTab.PURCHASED);layout(view)
            assertEquals(3781,grid.adapter!!.itemCount)
            view.selectTab(LibraryTab.GAME_PASS);layout(view)
            assertEquals(560,grid.adapter!!.itemCount)
        } finally { release.countDown();view.close();controller.pause().stop().destroy() }
    }

    @Test fun reorderingThousandsOfHydratedNamesKeepsFocusedProductAndUsesOneReplacement() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val gallery=source("purchased",OfficialGalleryKind.PURCHASED)
        var selected: GalleryProduct?=null
        val view=GameDashboardView(activity,GameLibrary(activity,"large-reorder"),{},{},{},{},onPlayProduct={selected=it})
        try {
            activity.setContentView(view)
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,gallery)
            view.selectTab(LibraryTab.PURCHASED);layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            val holder=grid.findViewHolderForAdapterPosition(2)!!
            holder.itemView.requestFocus();holder.itemView.performClick()
            val wanted=selected!!.productId
            var replacements=0;var moves=0
            grid.adapter!!.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
                override fun onChanged() { replacements++ }
                override fun onItemRangeMoved(fromPosition: Int,toPosition: Int,itemCount: Int) { moves+=itemCount }
            })
            val hydrated=gallery.copy(products=gallery.products.sortedBy { it.productId }.mapIndexed { index,p ->
                p.copy(name="Nome %04d".format(gallery.products.size-index))
            },metadataComplete=true)
            repeat(20) { view.updateOfficialGallery(hydrated) }
            layout(view)
            assertEquals(3781,grid.adapter!!.itemCount)
            assertEquals(1,replacements);assertEquals(0,moves)
            val focused=grid.focusedChild
            assertNotNull("Focused product must remain reachable after hydration changes its position",focused)
            focused.performClick();assertEquals(wanted,selected!!.productId)
            assertTrue(selected!!.name!!.startsWith("Nome"))
        } finally { view.close();controller.pause().stop().destroy() }
    }

    @Test fun pausingOrClosingWithCatalogWorkInFlightPreventsLateUiUpdates() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        val gallery=source("purchased",OfficialGalleryKind.PURCHASED)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val first=AtomicBoolean(true)
        val guarded=object: AbstractList<GalleryProduct>() {
            override val size get()=gallery.products.size
            override fun get(index: Int): GalleryProduct {
                if(first.compareAndSet(true,false)) { entered.countDown();check(release.await(8,TimeUnit.SECONDS)) }
                return gallery.products[index]
            }
        }
        val view=GameDashboardView(activity,GameLibrary(activity,"large-paused"),{},{},{},{})
        try {
            activity.setContentView(view)
            view.setOfficialGallery(OfficialGalleryKind.PURCHASED,gallery.copy(products=guarded))
            view.selectTab(LibraryTab.PURCHASED)
            awaitStarted(entered)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            view.pauseArtwork();release.countDown();view.awaitCatalog()
            assertEquals(0,grid.adapter!!.itemCount)
            view.resumeArtwork();layout(view)
            assertEquals(3781,grid.adapter!!.itemCount)
            var notifications=0
            grid.adapter!!.registerAdapterDataObserver(object: RecyclerView.AdapterDataObserver() {
                override fun onChanged() { notifications++ }
                override fun onItemRangeChanged(positionStart: Int,itemCount: Int,payload: Any?) { notifications++ }
            })
            view.setSearch(gallery.products.last().productId)
            val beforeClose=notifications
            view.close()
            shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.SECONDS)
            assertEquals(beforeClose,notifications);assertNull(grid.adapter)
        } finally { release.countDown();view.close();controller.pause().stop().destroy() }
    }

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
