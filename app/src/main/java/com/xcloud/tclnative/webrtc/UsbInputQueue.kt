package com.xcloud.tclnative.webrtc

/** Coalesce motion only. Button and trigger edges survive ordinary bursts; overload begins neutral. */
internal class UsbInputQueue(private val capacity: Int=64) {
    data class Entry(val state: GamepadState,val at: Long)
    private val queue=java.util.ArrayDeque<Entry>()
    @Volatile var overflows=0L;private set
    @Volatile var coalesced=0L;private set
    @Synchronized fun offer(state: GamepadState,at: Long) {
        val last=queue.lastOrNull()?.state
        if(last!=null && last.buttons==state.buttons && (last.leftTrigger>0)==(state.leftTrigger>0) &&
            (last.rightTrigger>0)==(state.rightTrigger>0)) { queue.removeLast();coalesced++ }
        if(queue.size>=capacity) { queue.clear();queue.add(Entry(GamepadState(),at));overflows++ }
        queue.add(Entry(state,at))
    }
    @Synchronized fun poll(): Entry? = if(queue.isEmpty()) null else queue.removeFirst()
    @Synchronized fun clear() { queue.clear() }
}
