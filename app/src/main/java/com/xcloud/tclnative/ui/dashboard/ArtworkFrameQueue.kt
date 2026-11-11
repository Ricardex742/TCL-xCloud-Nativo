package com.xcloud.tclnative.ui.dashboard

import android.view.Choreographer

/** Bound the number of new textures applied to the catalog in one frame, including memory-cache hits. */
internal class ArtworkFrameQueue(private val schedule: ((() -> Unit) -> Unit) = { action ->
    Choreographer.getInstance().postFrameCallback { action() }
},private val budget: Int=2) {
    private val pending=linkedMapOf<Any,()->Unit>()
    private var scheduled=false
    fun post(key: Any,action: () -> Unit) {
        pending[key]=action
        if(!scheduled) { scheduled=true; schedule(::frame) }
    }
    fun remove(key: Any) { pending.remove(key) }
    fun clear() { pending.clear() }
    private fun frame() {
        repeat(minOf(budget,pending.size)) {
            val key=pending.keys.firstOrNull() ?: return@repeat
            pending.remove(key)?.invoke()
        }
        if(pending.isNotEmpty()) schedule(::frame) else scheduled=false
    }
}
