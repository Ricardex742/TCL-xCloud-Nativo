package com.xcloud.tclnative.webrtc

/** The touch/virtual source retains logical slot zero even when no physical gamepad sends reports. */
class GamepadConnections {
    private val connected = mutableSetOf(0) // Announced once by ChannelHandshake.
    @Synchronized fun add(index: Int): Boolean {
        require(index in 0..3)
        return connected.add(index)
    }
    @Synchronized fun remove(index: Int): Boolean {
        require(index in 0..3)
        return index != 0 && connected.remove(index)
    }
}
