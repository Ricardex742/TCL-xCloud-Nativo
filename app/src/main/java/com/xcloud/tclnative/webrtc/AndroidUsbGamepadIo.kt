package com.xcloud.tclnative.webrtc

import android.hardware.usb.*
import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException

/** Interrupt endpoints use UsbRequest. Only the session thread dequeues completions. */
@RequiresApi(26)
internal class AndroidUsbGamepadIo(private val connection: UsbDeviceConnection,private val intf: UsbInterface,
    private val input: UsbEndpoint,private val output: UsbEndpoint): UsbGamepadSession.Io {
    private val read=UsbRequest();private val write=UsbRequest()
    private val incoming=ByteBuffer.allocateDirect(input.maxPacketSize)
    private val outgoing=ByteBuffer.allocateDirect(8)
    private val bytes=ByteArray(input.maxPacketSize)
    private var readReady=false;private var writeReady=false;private var pending=false
    private var inputEnabled=false
    override fun claim(force: Boolean)=connection.claimInterface(intf,force)
    override fun startInput(): Boolean {
        inputEnabled=true
        if(!readReady) { readReady=read.initialize(connection,input);if(!readReady) return false }
        incoming.clear();return read.queue(incoming)
    }
    override fun poll(timeoutMs: Long): UsbGamepadSession.Completion {
        val completed=try { connection.requestWait(timeoutMs) }
            catch(_: TimeoutException) { return UsbGamepadSession.Completion.Timeout }
        return when(completed) {
            read -> {
                if(!inputEnabled) return UsbGamepadSession.Completion.Timeout
                val size=incoming.position();incoming.flip();incoming.get(bytes,0,size)
                if(!startInput()) throw IOException("USB_INPUT_REQUEUE")
                UsbGamepadSession.Completion.Input(bytes,size)
            }
            write -> {
                pending=false
                if(outgoing.position()!=8) throw IOException("USB_OUTPUT_SHORT")
                UsbGamepadSession.Completion.Output
            }
            else -> throw IOException("USB_COMPLETION_FAILED")
        }
    }
    override fun write(packet: ByteArray): Boolean {
        if(pending) return false
        if(!writeReady) { writeReady=write.initialize(connection,output);if(!writeReady) return false }
        outgoing.clear();outgoing.put(packet);outgoing.flip()
        pending=write.queue(outgoing);return pending
    }
    override fun cancelInput() { inputEnabled=false;if(readReady) read.cancel() }
    override fun release() { connection.releaseInterface(intf) }
    override fun close() {
        runCatching { read.cancel() };runCatching { write.cancel() }
        runCatching { read.close() };runCatching { write.close() };connection.close()
    }
}
