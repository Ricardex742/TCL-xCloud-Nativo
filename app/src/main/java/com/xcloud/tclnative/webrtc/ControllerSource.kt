package com.xcloud.tclnative.webrtc

/** App-local identity; a USB controller does not pretend to be an Android InputDevice. */
sealed interface ControllerSource {
    data class Android(val deviceId: Int): ControllerSource
    data class Usb(val connection: String,val vendor: Int,val product: Int,val intf: Int,val name: String): ControllerSource {
        val profileKey get()="usb:$vendor:$product:$intf"
    }
}
