package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.catalog.XboxProfile
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
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
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DashboardTest {
    private val games = listOf("Forza Horizon 5", "33 Immortals", "Battlefield 1", "Diablo IV",
        "Gears of War", "Delta Force", "Sea of Thieves", "Halo Infinite", "Grounded", "Minecraft",
        "Microsoft Flight Simulator", "Indiana Jones").mapIndexed { i, name -> GameTitle("game-$i", name, "product-$i", seriesOptimized = i % 2 == 0) }

    private fun layout(view: View, w: Int = 1280, h: Int = 948) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        // The test Activity's window traversal uses its default display; lay out the component
        // once more at the requested export dimensions after draining that traversal.
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
    }
    private fun key(view: GameDashboardView, code: Int) {
        assertTrue(view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertTrue(view.handleKey(KeyEvent(KeyEvent.ACTION_UP, code)))
    }
    @Test fun shoulderTabsWrapAndFavoritesSurviveViewRecreationWithoutLeakingBetweenAccounts() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val library = GameLibrary(activity, "account-a")
        activity.getSharedPreferences("library_"+GameLibrary.accountKey("account-a"), 0).edit().clear().commit()
        val view = GameDashboardView(activity, library, {}, {}, {}, {})
        activity.setContentView(view); view.setGames(games); layout(view)
        assertEquals(LibraryTab.ALL, view.activeTab)
        key(view, KeyEvent.KEYCODE_BUTTON_Y)
        assertTrue(games.first().titleId in GameLibrary(activity, "account-a").favorites)
        assertTrue(GameLibrary(activity, "account-b").favorites.isEmpty())
        key(view, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(LibraryTab.RECENT, view.activeTab)
        key(view, KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(LibraryTab.FAVORITES, view.activeTab)
        assertEquals(games.first().titleId, view.selectedGame?.titleId)
        key(view, KeyEvent.KEYCODE_BUTTON_Y)
        assertNull(view.selectedGame)
        assertTrue(library.favorites.isEmpty())
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun choosingACoverLaunchesItsRealCloudIdAndSearchIncludesTheFullCatalog() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var played: GameTitle? = null
        val view = GameDashboardView(activity, GameLibrary(activity, "selection"), { played = it }, {}, {}, {})
        activity.setContentView(view)
        view.setGames(games + (12..260).map { GameTitle("cloud-$it", "Jogo $it") })
        layout(view)
        view.setSearch("Jogo 260"); layout(view)
        val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
        assertEquals(1, grid.adapter!!.itemCount)
        grid.getChildAt(0).performClick()
        assertEquals("cloud-260", played?.titleId)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun recentHistoryPersistsPerAccountAcrossCatalogRecreation() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("library_"+GameLibrary.accountKey("history"), 0).edit().clear().commit()
        val library = GameLibrary(activity, "history")
        library.recordPlayed("FORZA", 100)
        library.recordPlayed("FORTNITE", 200)
        library.recordPlayed("FORZA", 300)
        val restored = GameLibrary(activity, "history").records()
        assertEquals(2, restored["FORZA"]?.launches)
        assertEquals(300L, restored["FORZA"]?.lastPlayed)
        assertTrue(GameLibrary(activity, "another-history").records().isEmpty())
        controller.pause().stop().destroy()
    }
    @Test fun directionalFocusUpdatesTheHeaderAndAStartsTheFocusedGame() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var played: GameTitle? = null
        val view = GameDashboardView(activity, GameLibrary(activity, "dpad"), { played = it }, {}, {}, {})
        activity.setContentView(view); view.setGames(games)
        view.restoreFocus() // Requested before RecyclerView has created any cover holders.
        layout(view, 1920, 1080)
        val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
        val first = grid.getChildAt(0)
        assertFalse("Custom card focus must not have Android's second focus rectangle", first.defaultFocusHighlightEnabled)
        assertTrue("Initial focus must reach the cover after layout", first.hasFocus())
        assertTrue(first.requestFocus())
        val right = first.focusSearch(View.FOCUS_RIGHT)
        assertNotNull(right)
        assertTrue(right.requestFocus())
        assertEquals(games[1].titleId, view.selectedGame?.titleId)
        val down = right.focusSearch(View.FOCUS_DOWN)
        assertNotNull(down)
        assertTrue(down.requestFocus())
        assertEquals(games[7].titleId, view.selectedGame?.titleId)
        key(view, KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(games[7].titleId, played?.titleId)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun nativeLayoutRendersAtReferenceAndTvSizesWithEqualPortraitCovers() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = GameDashboardView(activity, GameLibrary(activity, "visual-preview"), {}, {}, {}, {})
        activity.setContentView(view)
        view.setGames(games)
        view.setGamePassProducts(setOf("product-0", "product-1", "product-3"))
        view.setProfile(XboxProfile("LUCAS", "LUCAS XBOX", subscription = "ULTIMATE", rewardsPoints = 12550))
        for ((w, h) in listOf(1280 to 948, 1920 to 1080, 3840 to 2160)) {
            layout(view, w, h)
            view.restoreFocus(); layout(view, w, h)
            val grid = (0 until view.childCount).map(view::getChildAt).filterIsInstance<RecyclerView>().single()
            assertTrue(grid.childCount >= 6)
            val row = (0 until 6).map(grid::getChildAt)
            assertTrue(row.all { !it.defaultFocusHighlightEnabled })
            assertTrue(row.all { it.width == row.first().width || kotlin.math.abs(it.width-row.first().width) <= 1 })
            assertTrue(row.all { it.height > it.width })
            assertTrue(grid.bottom < h)
            assertEquals(w, view.width)
            assertTrue(grid.right > w * .9f)
            assertTrue(grid.width > w * .8f)
            val icon = (0 until view.childCount).map(view::getChildAt).filterIsInstance<android.widget.ImageView>()
                .first { it.contentDescription?.startsWith("Ícone de") == true }
            assertTrue("Full game header must fit above the tabs and grid", icon.bottom < grid.top)
            assertTrue("Game icon must remain large on TV", icon.height >= w * .085f)
            assertTrue(grid.height > grid.getChildAt(0).height)
            grid.getChildAt(0).requestFocus()
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/reports/dashboard/catalog-${w}x$h.png").apply { parentFile?.mkdirs() }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        view.close(); controller.pause().stop().destroy()
    }
}
