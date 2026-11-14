package com.xcloud.tclnative.webrtc

import java.util.concurrent.atomic.AtomicBoolean

/** One completion consumer owns both directions. Transport calls and callbacks run off the UI thread. */
internal class UsbGamepadSession(private val io: Io,private val exclusive: Boolean,
    private val clock: () -> Long,private val onPacket: (ByteArray,Int,GamepadState?,Long) -> Unit,
    private val onReady: () -> Unit) {
    sealed interface Completion {
        data class Input(val data: ByteArray,val length: Int): Completion
        data object Output: Completion
        data object Timeout: Completion
    }
    interface Io: AutoCloseable {
        fun claim(force: Boolean): Boolean
        fun startInput(): Boolean
        fun poll(timeoutMs: Long): Completion
        fun write(packet: ByteArray): Boolean
        fun cancelInput()
        fun release()
    }
    private val running=AtomicBoolean(true)
    val rumble=UsbRumbleEnvelope()
    fun stop() { running.set(false);rumble.stop() }
    fun run() {
        var claimed=false;var outputPending=false;var sent: Pair<Int,Int>?=null
        try {
            claimed=io.claim(false) || (exclusive && io.claim(true))
            check(claimed) { "USB_INTERFACE_BUSY" }
            if(!running.get()) return
            check(io.startInput()) { "USB_INPUT_QUEUE" }
            onReady()
            while(running.get()) {
                val levels=rumble.levels(clock())
                if(!outputPending && levels!=sent) {
                    check(io.write(UsbXInputProtocol.packet(levels.first,levels.second))) { "USB_OUTPUT_QUEUE" }
                    sent=levels;outputPending=true
                }
                when(val result=io.poll(8)) {
                    is Completion.Input -> onPacket(result.data,result.length,UsbXInputParser.parse(result.data,result.length),clock())
                    Completion.Output -> outputPending=false
                    Completion.Timeout -> Unit // Controllers may report only changes. Preserve held input on silence.
                }
            }
        } finally {
            if(claimed) {
                runCatching { io.cancelInput() }
                // Complete a pending OUT before reusing its buffer, then send a final stop within a bounded wait.
                runCatching {
                    val deadline=clock()+200
                    var attempts=0
                    while(outputPending && clock()<deadline && attempts++<25)
                        if(io.poll(8)==Completion.Output) outputPending=false
                    if(!outputPending && io.write(UsbXInputProtocol.packet(0,0))) {
                        attempts=0
                        while(clock()<deadline && attempts++<25) if(io.poll(8)==Completion.Output) break
                    }
                }
                runCatching { io.release() }
            }
            io.close()
        }
    }
}
