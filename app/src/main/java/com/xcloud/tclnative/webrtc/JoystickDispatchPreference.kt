package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import android.os.Build
import android.view.InputDevice
import android.view.View

/** Owns only the app root's joystick request. Touch and pointer buffering keep their defaults. */
class JoystickDispatchPreference(private val root: View) {
    private var requested=false
    private var rejected=false
    private var requestedDuringSession=false
    fun beginSession() { request(false);requestedDuringSession=false;rejected=false }
    fun request(enabled: Boolean) {
        if(Build.VERSION.SDK_INT<30) return
        val immediate=enabled && root.isAttachedToWindow
        try {
            root.requestUnbufferedDispatch(if(immediate) InputDevice.SOURCE_CLASS_JOYSTICK else InputDevice.SOURCE_CLASS_NONE)
            requested=immediate;rejected=false
            requestedDuringSession=requestedDuringSession || immediate
        } catch(_: RuntimeException) { requested=false;rejected=true }
    }
    fun diagnostics(): String = UiText.join("Joystick Android: ",(when {
        Build.VERSION.SDK_INT<30 -> UiText.text("despacho imediato indisponível nesta API")
        rejected -> UiText.text("pedido de despacho imediato recusado")
        requested -> UiText.text("despacho imediato solicitado (sem espera pelo vsync da interface)")
        else -> UiText.text("despacho padrão")
    }),if(Build.VERSION.SDK_INT>=30) UiText.text("; pedido durante a sessão: {0}",UiText.nested(if(requestedDuringSession) UiText.text("sim") else UiText.text("não"))) else "")
}
