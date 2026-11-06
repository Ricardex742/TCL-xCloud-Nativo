package com.xcloud.tclnative.webrtc

import java.util.ArrayDeque

data class TouchInputDimensions(val width: Int, val height: Int) {
    init { require(width in 1..16384 && height in 1..16384) }
}

data class NativePointer(val id: Int, val x: Float, val y: Float, val type: Int, val pressure: Float = 1f,
                         val width: Float = 1f/1920, val height: Float = 1f/1080) {
    init { require(id in 0..32 && type in 1..3 && x.isFinite() && y.isFinite() && pressure.isFinite() &&
        width.isFinite() && height.isFinite() && width >= 0 && height >= 0) }
}

/** Keep contact transitions, coalesce motion, and resync contacts after a stalled channel overflows. */
class NativeTouchBuffer {
    private val queue=ArrayDeque<List<NativePointer>>()
    private val active=mutableMapOf<Int,NativePointer>()
    private val known=mutableMapOf<Int,NativePointer>()
    private var receivedDown=0L; private var receivedUp=0L; private var receivedMove=0L
    private var sentDown=0L; private var sentUp=0L; private var sentMove=0L
    private var mergedMoves=0L; private var resyncs=0L; private var peakBatches=0
    @Synchronized fun add(events: List<NativePointer>) {
        require(events.size<=10)
        if(events.isEmpty()) return
        receivedDown+=events.count { it.type==1 };receivedUp+=events.count { it.type==2 };receivedMove+=events.count { it.type==3 }
        events.forEach { p -> known[p.id]=p; if(p.type==2) active.remove(p.id) else active[p.id]=p }
        val previous=queue.lastOrNull()
        if(events.all { it.type==3 } && previous?.all { it.type==3 }==true) {
            // Partial updates may concern different fingers: keep each one's latest position.
            val latest=if(previous.all { old -> events.any { it.id==old.id } }) events.toList()
                else (previous+events).associateBy { it.id }.values.toList()
            if(latest.size<=10) {
                mergedMoves+=previous.size+events.size-latest.size
                queue.removeLast();queue.addLast(latest)
            } else queue.addLast(events.toList())
        } else queue.addLast(events.toList())
        peakBatches=maxOf(peakBatches,queue.size)
        if(queue.size>32) {
            resyncs++
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
    /** Called only after DataChannel.send accepted the actual packet; not a game acknowledgement. */
    @Synchronized fun accepted(events: List<NativePointer>) {
        sentDown+=events.count { it.type==1 };sentUp+=events.count { it.type==2 };sentMove+=events.count { it.type==3 }
    }
    @Synchronized fun diagnostics(): String = "Fila de toque nativo: entrada D/U/M $receivedDown/$receivedUp/$receivedMove; "+
        "aceitos pelo canal D/U/M $sentDown/$sentUp/$sentMove; lotes pendentes ${queue.size}, pico $peakBatches; "+
        "movimentos consolidados $mergedMoves; ressincronizações $resyncs"
    @Synchronized fun release() {
        known.values.filter { it.type!=2 }.map { it.copy(type=2,pressure=0f) }.chunked(10).forEach(::add)
        active.clear()
    }
}
