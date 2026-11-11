package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.signaling.QueueEstimate
import com.xcloud.tclnative.signaling.QueueStatus
import com.xcloud.tclnative.ui.dashboard.GameLoadingView
import com.xcloud.tclnative.ui.dashboard.LoadingPhase
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
class LoadingScreenTest {
    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun layout(view: View, w: Int, h: Int) {
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            if (it == 0) shadowOf(Looper.getMainLooper()).idle()
        }
    }
    @Test fun queueShowsTheServiceEstimateElapsedTimeAndStalenessWithoutAFakeCountdown() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var cancelled = 0
        val view = GameLoadingView(activity) { cancelled++ }
        activity.setContentView(view)
        view.begin(GameTitle("FORZA", "Forza Horizon 5"), 0)
        view.updateQueue(QueueStatus(0, 7000, QueueEstimate(787), 0), 8000)
        val text = labels(view).joinToString("\n")
        assertTrue(text.contains("Cerca de 13 min 7s"))
        assertTrue(text.contains("8s"))
        assertFalse(text.contains("%"))
        view.render(128_000)
        val stale = labels(view).joinToString("\n")
        assertTrue(stale.contains("Cerca de 13 min 7s"))
        assertTrue(stale.contains("2 min 8s"))
        assertTrue(stale.contains("desatualizada"))
        view.cancelFromController(); assertEquals(1, cancelled)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun leavingQueueKeepsGameBackdropAndShowsConnectionStagesUntilTheFirstFrame() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = GameLoadingView(activity) {}
        activity.setContentView(view)
        view.begin(GameTitle("FORTNITE", "Fortnite"), 0)
        view.updateQueue(QueueStatus(0, 0), 1000)
        assertEquals(LoadingPhase.QUEUED, view.phase)
        assertTrue(labels(view).contains("Consultando…"))
        view.updateQueue(null)
        view.updateProgress("SDP: negotiation")
        assertEquals(LoadingPhase.CONNECTING, view.phase)
        assertEquals(View.VISIBLE, view.visibility)
        view.updateProgress("VIDEO:")
        assertEquals(LoadingPhase.VIDEO, view.phase)
        assertTrue(labels(view).contains("RECEBENDO A IMAGEM"))
        view.dismiss(); assertEquals(View.GONE, view.visibility)
        view.close(); controller.pause().stop().destroy()
    }
    @Test fun exportThemedQueueAtPhoneAndTvSizesWithAccessibleCancelButton() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val view = GameLoadingView(activity) {}
        activity.setContentView(view)
        view.begin(GameTitle("FORZA", "Forza Horizon 5", gamePass = true, seriesOptimized = true), 0)
        view.updateQueue(QueueStatus(0, 7000, QueueEstimate(787), 0), 8000)
        for ((w,h) in listOf(1280 to 590, 1920 to 1080, 3840 to 2160)) {
            layout(view, w, h)
            val cancel = (0 until view.childCount).map(view::getChildAt)
                .first { it.contentDescription?.startsWith("Cancelar espera") == true }
            assertTrue(cancel.top > 0 && cancel.bottom < h)
            assertTrue(cancel.isFocusable && cancel.isClickable)
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File("build/reports/dashboard/queue-${w}x$h.png").apply { parentFile?.mkdirs() }
                .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        view.close(); controller.pause().stop().destroy()
    }
}
