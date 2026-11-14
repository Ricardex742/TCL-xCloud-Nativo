package com.xcloud.tclnative.webrtc

/** One replaceable command, independent motors and bounded deadlines; no per-pulse jobs accumulate. */
class UsbRumbleEnvelope {
    private var command: RumbleCommand?=null
    private var began=0L
    @Synchronized fun set(value: RumbleCommand,now: Long) { command=value;began=now }
    @Synchronized fun stop() { command=null }
    @Synchronized fun levels(now: Long): Pair<Int,Int> {
        val value=command ?: return 0 to 0
        val duration=value.durationMs.coerceIn(0,5000).toLong()
        val delay=value.delayMs.coerceIn(0,5000).toLong()
        val elapsed=(now-began).coerceAtLeast(0)
        if(duration==0L || elapsed<delay) return 0 to 0
        val offset=elapsed-delay;val period=duration+delay
        if(offset/period>value.repeat.coerceIn(0,15)) { command=null;return 0 to 0 }
        return if(offset%period<duration) value.strong.coerceIn(0,100) to value.weak.coerceIn(0,100) else 0 to 0
    }
}
