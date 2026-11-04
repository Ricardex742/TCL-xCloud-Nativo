package com.xcloud.tclnative.webrtc

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

data class GamepadState(
    val buttons: Int = 0,
    val leftX: Float = 0f, val leftY: Float = 0f,
    val rightX: Float = 0f, val rightY: Float = 0f,
    val leftTrigger: Float = 0f, val rightTrigger: Float = 0f
)

/** Common gamepad report in the negotiated input/1.0 channel (little-endian header). */
object GamepadPacket {
    fun metadata(timestampMs: Double, maxTouchPoints: Int = 0): ByteArray =
        header(15, 8, 0, timestampMs).put(maxTouchPoints.coerceIn(0,10).toByte()).array()
    fun encode(state: GamepadState, sequence: Int, timestampMs: Double): ByteArray = encode(mapOf(0 to state),sequence,timestampMs)
    fun encode(states: Map<Int,GamepadState>, sequence: Int, timestampMs: Double,
               pointers: List<NativePointer> = emptyList(), width: Int = 1920, height: Int = 1080): ByteArray {
        require(states.size<=4 && states.keys.all { it in 0..3 } && pointers.size<=10)
        val type=(if(states.isEmpty()) 0 else 2) or (if(pointers.isEmpty()) 0 else 4)
        val out=header(14+(if(states.isEmpty()) 0 else 1+23*states.size)+(if(pointers.isEmpty()) 0 else 2+20*pointers.size),type,sequence,timestampMs)
        if(states.isNotEmpty()) {
            out.put(states.size.toByte())
            states.toSortedMap().forEach { (index,state) ->
                out.put(index.toByte()).putShort(state.buttons.toShort())
        out.putShort(axis(state.leftX)).putShort(axis(-state.leftY))
            .putShort(axis(state.rightX)).putShort(axis(-state.rightY))
            .putShort(trigger(state.leftTrigger)).putShort(trigger(state.rightTrigger))
            .putInt(1)
        // VirtualPhysicality is the sole big-endian uint32 in this report.
        out.order(ByteOrder.BIG_ENDIAN).putInt(1)
                out.order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        if(pointers.isNotEmpty()) {
            out.put(1).put(pointers.size.toByte())
            pointers.forEach { p ->
                // Match DataView's integer conversion in the official reliable input serializer.
                out.putShort((p.height.coerceIn(0f,1f)*height).toInt().coerceIn(0,65535).toShort())
                    .putShort((p.width.coerceIn(0f,1f)*width).toInt().coerceIn(0,65535).toShort())
                    .put((if(p.type==2) 0 else (p.pressure.coerceIn(0f,1f)*255).toInt()).toByte())
                    .putShort(0).putInt(p.id)
                    .putInt((p.x.coerceIn(0f,1f)*width.coerceAtLeast(1)).toInt())
                    .putInt((p.y.coerceIn(0f,1f)*height.coerceAtLeast(1)).toInt()).put(p.type.toByte())
            }
        }
        return out.array()
    }
    private fun header(size: Int, type: Int, sequence: Int, time: Double) = ByteBuffer.allocate(size)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(type.toShort()).putInt(sequence).putDouble(time)
    private fun axis(value: Float) = (if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f)
        .times(32767).roundToInt().toShort()
    private fun trigger(value: Float) = (if (value.isFinite()) value.coerceIn(0f, 1f) else 0f)
        .times(65535).toInt().toShort()
}
