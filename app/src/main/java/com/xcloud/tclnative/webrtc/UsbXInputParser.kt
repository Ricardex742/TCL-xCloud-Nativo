package com.xcloud.tclnative.webrtc

/** Standard Xbox 360 input only: status messages and unverified vendor extensions are not buttons. */
object UsbXInputParser {
    private val firstMasks=intArrayOf(256,512,1024,2048,4,8,16384,32768)
    private val secondMasks=intArrayOf(4096,8192,2,0,16,32,64,128)
    fun parse(data: ByteArray,length: Int=data.size): GamepadState? {
        if(length!=20 || data.size<length || data[0].toInt()!=0 || (data[1].toInt() and 255)!=20) return null
        fun unsigned(at: Int)=data[at].toInt() and 255
        fun axis(at: Int): Float {
            val raw=(unsigned(at) or (unsigned(at+1) shl 8)).toShort().toInt()
            return if(raw<0) raw/32768f else raw/32767f
        }
        val first=unsigned(2);val second=unsigned(3)
        var buttons=0
        for(index in 0..7) {
            if(first and (1 shl index)!=0) buttons=buttons or firstMasks[index]
            if(second and (1 shl index)!=0) buttons=buttons or secondMasks[index]
        }
        fun y(at: Int)=axis(at).let { if(it==0f) 0f else -it }
        return GamepadState(buttons,axis(6),y(8),axis(10),y(12),unsigned(4)/255f,unsigned(5)/255f)
    }
}
