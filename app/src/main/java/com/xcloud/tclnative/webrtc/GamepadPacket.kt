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
    fun metadata(timestampMs: Double): ByteArray = header(15, 8, 0, timestampMs).put(0).array()
    fun encode(state: GamepadState, sequence: Int, timestampMs: Double): ByteArray {
        val out = header(38, 2, sequence, timestampMs)
        out.put(1).put(0).putShort(state.buttons.toShort())
        out.putShort(axis(state.leftX)).putShort(axis(-state.leftY))
            .putShort(axis(state.rightX)).putShort(axis(-state.rightY))
            .putShort(trigger(state.leftTrigger)).putShort(trigger(state.rightTrigger))
            .putInt(1)
        // VirtualPhysicality is the sole big-endian uint32 in this report.
        out.order(ByteOrder.BIG_ENDIAN).putInt(1)
        return out.array()
    }
    private fun header(size: Int, type: Int, sequence: Int, time: Double) = ByteBuffer.allocate(size)
        .order(ByteOrder.LITTLE_ENDIAN).putShort(type.toShort()).putInt(sequence).putDouble(time)
    private fun axis(value: Float) = (if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f)
        .times(32767).roundToInt().toShort()
    private fun trigger(value: Float) = (if (value.isFinite()) value.coerceIn(0f, 1f) else 0f)
        .times(65535).toInt().toShort()
}
