package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.signaling.*
import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.mockwebserver.*
import okio.Buffer
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

/** Reproducible captures of production views with a demo account and public cover crops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShowcaseCaptureTest {
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1280,720); shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun save(view: View,name: String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/showcase/$name.png").apply { parentFile.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val limit=System.currentTimeMillis()+10_000
        while(!condition() && System.currentTimeMillis()<limit) { Thread.sleep(15); shadowOf(Looper.getMainLooper()).idle() }
        assertTrue("Artwork did not reach the native view",condition())
    }
    @Test fun realArtworkLoadingAndFavoriteStateAreVisibleInThePublicDemoCaptures() {
        val server=MockWebServer()
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val index=request.path!!.substringAfter("poster-").substringBefore('.').toInt()
                val bytes=javaClass.getResourceAsStream("/showcase/poster-$index.png")!!.use { it.readBytes() }
                return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes))
            }
        }
        server.start()
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val games=listOf("007 First Light","1000xRESIST","15 in 1 Solitaire","If You're a Ghost Call Me Here","30 Birds","33 Immortals")
            .mapIndexed { i,name -> GameTitle("DEMO-$i",name,"DEMO-PRODUCT-$i",posterUrl=server.url("/poster-$i.png").toString(),
                tileUrl=server.url("/poster-$i.png").toString(),seriesOptimized=i!=2) }
        val library=GameLibrary(activity,"showcase-demo").apply { toggleFavorite(games[1].titleId) }
        val view=GameDashboardView(activity,library,{}, {}, {}, {})
        try {
            activity.setContentView(view); view.setGames(games); view.selectTab(com.xcloud.tclnative.catalog.LibraryTab.ALL)
            view.setGamePassProducts(setOf("DEMO-PRODUCT-1","DEMO-PRODUCT-5"))
            view.setProfile(XboxProfile("JOGADOR","PERFIL DEMO",subscription="ULTIMATE"))
            layout(view); view.restoreFocus(); layout(view)
            val grid=all(view).filterIsInstance<RecyclerView>().single()
            await { grid.childCount>=6 && (0 until 6).all { i -> all(grid.getChildAt(i)).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable } } }
            assertEquals(6,grid.adapter!!.itemCount)
            save(view,"catalog")
            val header=all(view).filterIsInstance<ImageView>().single { it.contentDescription=="Ícone de ${games[0].name}" }
            val oldHeader=header.drawable
            view.setRecentHistory(listOf(games[5].titleId,games[1].titleId,games[0].titleId))
            view.selectTab(com.xcloud.tclnative.catalog.LibraryTab.RECENT); view.restoreFocus(); layout(view)
            await { grid.childCount>=3 && (0 until 3).all { i -> all(grid.getChildAt(i)).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable } } }
            assertEquals(games[5].titleId,view.selectedGame?.titleId)
            shadowOf(Looper.getMainLooper()).idleFor(200,java.util.concurrent.TimeUnit.MILLISECONDS)
            await { header.drawable is BitmapDrawable && header.drawable!==oldHeader }
            save(view,"return-to-play")
            view.selectTab(com.xcloud.tclnative.catalog.LibraryTab.ALL); layout(view)
            grid.getChildAt(1).requestFocus(); layout(view)
            assertEquals(games[1].titleId,view.selectedGame?.titleId)
            val loading=GameLoadingView(activity) {}
            activity.setContentView(loading)
            loading.begin(games[1].copy(gamePass=true),0)
            loading.updateQueue(QueueStatus(0,7000,QueueEstimate(180),0),8000)
            layout(loading)
            await { all(loading).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable } }
            save(loading,"queue"); loading.close()
        } finally { view.close(); controller.pause().stop().destroy(); server.shutdown() }
    }
}
