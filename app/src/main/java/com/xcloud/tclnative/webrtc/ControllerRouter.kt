package com.xcloud.tclnative.webrtc

import kotlin.math.abs

/** Slots remain stable until a device disconnects; input from different players is never merged. */
class ControllerRouter {
    private val devices=mutableMapOf<ControllerSource,Int>()
    private val states=Array(4) { GamepadState() }
    var coOp=false
        private set
    private var virtual=GamepadState()
    private var reserveTouch=false
    fun beginSession(enabled: Boolean, touchPlayerOne: Boolean = false) { devices.clear(); coOp=enabled; reserveTouch=enabled && touchPlayerOne; reset() }
    fun slot(deviceId: Int): Int? = slot(ControllerSource.Android(deviceId))
    fun slot(source: ControllerSource): Int? = devices[source] ?: ((if(reserveTouch) 1 else 0) until (if(coOp) 4 else 1))
        .firstOrNull { it !in devices.values }?.also { devices[source]=it }
    fun sourceFor(slot: Int): ControllerSource? = devices.entries.firstOrNull { it.value==slot }?.key
    fun deviceFor(slot: Int): Int? = (sourceFor(slot) as? ControllerSource.Android)?.deviceId
    fun remove(deviceId: Int): Int? = remove(ControllerSource.Android(deviceId))
    fun remove(source: ControllerSource): Int? = devices.remove(source)?.also { states[it]=GamepadState() }
    fun attachUsb(source: ControllerSource.Usb,androidId: Int?): Int? {
        val previous=androidId?.let { remove(it) }
        if(previous!=null) { devices[source]=previous;return previous }
        return slot(source)
    }
    fun physical(slot: Int) = states[slot]
    fun update(slot: Int, state: GamepadState) { states[slot]=state }
    fun virtual(state: GamepadState) { virtual=state }
    fun combined(slot: Int, physicalOverride: GamepadState? = null) =
        if(slot==0) merge(physicalOverride ?: states[0],virtual) else physicalOverride ?: states[slot]
    fun reset() { states.indices.forEach { states[it]=GamepadState() }; virtual=GamepadState() }
    companion object {
        fun merge(physical: GamepadState, virtual: GamepadState): GamepadState {
            fun axis(a: Float,b: Float) = if(abs(b)>abs(a)) b else a
            return physical.copy(buttons=physical.buttons or virtual.buttons,
                leftX=axis(physical.leftX,virtual.leftX),leftY=axis(physical.leftY,virtual.leftY),
                rightX=axis(physical.rightX,virtual.rightX),rightY=axis(physical.rightY,virtual.rightY),
                leftTrigger=maxOf(physical.leftTrigger,virtual.leftTrigger),rightTrigger=maxOf(physical.rightTrigger,virtual.rightTrigger))
        }
    }
}
