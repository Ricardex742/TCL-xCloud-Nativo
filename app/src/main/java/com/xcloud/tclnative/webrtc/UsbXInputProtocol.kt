package com.xcloud.tclnative.webrtc

/** Xbox 360 wired/XInput only. HID, Xbox One and wireless Xbox protocols differ. */
object UsbXInputProtocol {
    fun supported(interfaceClass: Int, subclass: Int, protocol: Int, alternate: Int,
                  interruptIn: Boolean, outputPacketSizes: List<Int>): Boolean =
        interfaceClass==0xff && subclass==0x5d && protocol==0x01 && alternate==0 &&
            interruptIn && outputPacketSizes.size==1 && outputPacketSizes.single()>=8

    fun packet(strong: Int, weak: Int): ByteArray = byteArrayOf(0,8,0,
        (strong.coerceIn(0,100)*255/100).toByte(),(weak.coerceIn(0,100)*255/100).toByte(),0,0,0)

    /** Refuse ambiguous receivers/controllers, including a second receiver with the same IDs. */
    fun inputDeviceId(vendor: Int,product: Int,usbIds: List<Pair<Int,Int>>,inputIds: List<Triple<Int,Int,Int>>): Int? {
        if(vendor<=0 || product<=0 || usbIds.count { it.first==vendor && it.second==product }!=1) return null
        return inputIds.filter { it.second==vendor && it.third==product }.map { it.first }.distinct().singleOrNull()
    }
}

/** Owns one claimed interface. A failed claim must never detach the kernel input driver. */
class UsbXInputSession private constructor(private val io: Io) : AutoCloseable {
    interface Io {
        fun claim(force: Boolean): Boolean
        fun write(packet: ByteArray): Boolean
        fun release()
        fun close()
    }
    private var closed=false
    fun write(strong: Int,weak: Int): Boolean = !closed && io.write(UsbXInputProtocol.packet(strong,weak))
    override fun close() {
        if(closed) return
        closed=true
        try { runCatching { io.write(UsbXInputProtocol.packet(0,0)) } }
        finally { try { io.release() } finally { io.close() } }
    }
    companion object {
        fun open(io: Io): UsbXInputSession? {
            try {
                if(io.claim(false)) return UsbXInputSession(io)
            } catch(_: RuntimeException) { }
            io.close()
            return null
        }
    }
}
