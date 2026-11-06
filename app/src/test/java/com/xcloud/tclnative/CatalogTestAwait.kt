package com.xcloud.tclnative

import android.os.Looper
import com.xcloud.tclnative.ui.dashboard.GameDashboardView
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Drain Main while the real catalog worker finishes; no synchronous production fast path. */
internal fun GameDashboardView.awaitCatalog() {
    val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
    while(isCatalogUpdating && System.nanoTime()<deadline) {
        shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)
        Thread.sleep(1)
    }
    shadowOf(Looper.getMainLooper()).idle()
    assertFalse("Catalog worker did not finish",isCatalogUpdating)
}
