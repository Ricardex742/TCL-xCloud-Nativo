package com.xcloud.tclnative.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RumbleCommand(val player: Int, val strong: Int, val weak: Int, val leftTrigger: Int,
                         val rightTrigger: Int, val durationMs: Int, val delayMs: Int, val repeat: Int) {
    val intensity: Int get() = maxOf(strong,weak,leftTrigger,rightTrigger).coerceIn(0,100)
}
data class RemoteInputReport(val width: Int? = null, val height: Int? = null, val rumble: RumbleCommand? = null) {
    companion object {
        fun parse(data: ByteBuffer): RemoteInputReport? {
            val bytes=data.slice().order(ByteOrder.LITTLE_ENDIAN)
            if(bytes.remaining()<2) return null
            val type=bytes.short.toInt() and 0xffff
            if(type and (16 or 128).inv()!=0) return null
            var width: Int?=null; var height: Int?=null
            if(type and 16!=0) {
                if(bytes.remaining()<8) return null
                val h=bytes.int; val w=bytes.int
                if(w in 1..16384 && h in 1..16384) { width=w; height=h }
            }
            var rumble: RumbleCommand?=null
            if(type and 128!=0) {
                if(bytes.remaining()<11) return null
                val kind=bytes.get().toInt() and 255
                val player=bytes.get().toInt() and 255
                val motors=IntArray(4) { (bytes.get().toInt() and 255).coerceAtMost(100) }
                val duration=bytes.short.toInt() and 65535
                val delay=bytes.short.toInt() and 65535
                val repeat=bytes.get().toInt() and 255
                if(kind==0 && player in 0..3) rumble=RumbleCommand(player,motors[0],motors[1],motors[2],motors[3],duration,delay,repeat)
            }
            return RemoteInputReport(width,height,rumble)
        }
    }
}
