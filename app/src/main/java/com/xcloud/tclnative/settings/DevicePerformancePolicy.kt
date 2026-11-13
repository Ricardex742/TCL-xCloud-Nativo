package com.xcloud.tclnative.settings

import android.app.ActivityManager
import android.content.Context

/** Use reported memory, not a box's marketing name, GPU label or Android version. */
data class DevicePerformancePolicy(val constrainedMemory: Boolean) {
    companion object {
        private const val MIB = 1024L * 1024L

        fun forDevice(totalMemoryBytes: Long, heapClassMb: Int, lowRam: Boolean) =
            DevicePerformancePolicy(lowRam || heapClassMb in 1..128 ||
                totalMemoryBytes in 1..(1280L * MIB))

        fun read(context: Context): DevicePerformancePolicy {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DevicePerformancePolicy(true)
            val memory = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(memory)
            return forDevice(memory.totalMem, manager.memoryClass, manager.isLowRamDevice)
        }
    }
}
