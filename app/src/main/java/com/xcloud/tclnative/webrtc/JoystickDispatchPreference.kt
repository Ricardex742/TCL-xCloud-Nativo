package com.xcloud.tclnative.webrtc

import android.os.Build
import android.view.InputDevice
import android.view.View

/** Owns only the app root's joystick request. Touch and pointer buffering keep their defaults. */
class JoystickDispatchPreference(private val root: View) {
    private var requested=false
    private var rejected=false
    fun request(enabled: Boolean) {
        if(Build.VERSION.SDK_INT<30) return
        val immediate=enabled && root.isAttachedToWindow
        try {
            root.requestUnbufferedDispatch(if(immediate) InputDevice.SOURCE_CLASS_JOYSTICK else InputDevice.SOURCE_CLASS_NONE)
            requested=immediate;rejected=false
        } catch(_: RuntimeException) { requested=false;rejected=true }
    }
    fun diagnostics(): String = "Joystick Android: "+when {
        Build.VERSION.SDK_INT<30 -> "despacho imediato indisponível nesta API"
        rejected -> "pedido de despacho imediato recusado"
        requested -> "despacho imediato solicitado (sem espera pelo vsync da interface)"
        else -> "despacho padrão"
    }
}
