package com.xcloud.tclnative

import android.app.ActivityManager
import android.app.Application
import org.robolectric.Shadows.shadowOf

/** Model a normal device explicitly; Robolectric otherwise reports an artificial 16 MB heap. */
class TestDeviceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        shadowOf(getSystemService(ActivityManager::class.java)).apply {
            setMemoryClass(256)
            setIsLowRamDevice(false)
            setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem=4L shl 30 })
        }
    }
}
