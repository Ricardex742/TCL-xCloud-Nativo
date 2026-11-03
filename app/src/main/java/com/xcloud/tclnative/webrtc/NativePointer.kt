package com.xcloud.tclnative.webrtc

import java.util.ArrayDeque

data class NativePointer(val id: Int, val x: Float, val y: Float, val type: Int, val pressure: Float = 1f,
                         val width: Float = 1f/1920, val height: Float = 1f/1080) {
    init { require(id in 0..31 && type in 1..3 && x.isFinite() && y.isFinite() && pressure.isFinite() &&
        width.isFinite() && height.isFinite() && width >= 0 && height >= 0) }
}

/** Keep contact transitions, coalesce motion, and resync contacts after a stalled channel overflows. */
class NativeTouchBuffer {
    private val queue=ArrayDeque<List<NativePointer>>()
    private val active=mutableMapOf<Int,NativePointer>()
    private val known=mutableMapOf<Int,NativePointer>()
    @Synchronized fun add(events: List<NativePointer>) {
        require(events.size<=10)
        if(events.isEmpty()) return
        events.forEach { p -> known[p.id]=p; if(p.type==2) active.remove(p.id) else active[p.id]=p }
        if(events.all { it.type==3 } && queue.lastOrNull()?.all { it.type==3 }==true) queue.removeLast()
        queue.addLast(events.toList())
        if(queue.size>32) {
            queue.clear()
            known.values.map { it.copy(type=2,pressure=0f) }.chunked(10).forEach(queue::addLast)
            active.values.map { it.copy(type=1) }.chunked(10).forEach(queue::addLast)
        }
    }
    @Synchronized fun next(): List<NativePointer> = if(queue.isEmpty()) emptyList() else queue.removeFirst().also { batch ->
        batch.filter { it.type==2 && it.id !in active }.forEach { known.remove(it.id) }
    }
    @Synchronized fun pending() = queue.isNotEmpty()
    @Synchronized fun nextCount() = queue.peekFirst()?.size ?: 0
    @Synchronized fun release() {
        known.values.filter { it.type!=2 }.map { it.copy(type=2,pressure=0f) }.chunked(10).forEach(::add)
        active.clear()
    }
}
