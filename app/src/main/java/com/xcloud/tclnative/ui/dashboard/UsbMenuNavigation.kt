package com.xcloud.tclnative.ui.dashboard

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Window
import com.xcloud.tclnative.webrtc.ControllerNavigation
import com.xcloud.tclnative.webrtc.GamepadState

internal class UsbMenuNavigation(private val activity: Activity) {
    private val handler=Handler(Looper.getMainLooper())
    private val state=ControllerNavigation()
    private var target: Window?=null
    private val repeat=object: Runnable {
        override fun run() {
            if(target!==AppControllerWindows.current(activity)) { clear();return }
            deliver(state.repeat(SystemClock.uptimeMillis()))
            if(state.repeating) handler.postDelayed(this,45)
        }
    }
    fun update(value: GamepadState) {
        val current=AppControllerWindows.current(activity)
        if(target!==current) clear()
        target=current ?: return
        deliver(state.update(value,SystemClock.uptimeMillis()))
        handler.removeCallbacks(repeat)
        if(state.repeating) handler.postDelayed(repeat,45)
    }
    private fun deliver(strokes: List<ControllerNavigation.Stroke>) {
        val window=target ?: return
        for(stroke in strokes) {
            if(window!==AppControllerWindows.current(activity)) { clear();return }
            val at=SystemClock.uptimeMillis()
            val handled=window.callback?.dispatchKeyEvent(KeyEvent(at,at,if(stroke.down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,
                stroke.key,stroke.repeat,0,KeyCharacterMap.VIRTUAL_KEYBOARD,0,0,InputDevice.SOURCE_GAMEPAD))==true
            // System events normally get this fallback in ViewRootImpl, outside Window.Callback.
            // Our app-local actions need the same focus step without global event injection.
            if(!handled && stroke.down) {
                val direction=when(stroke.key) {
                    KeyEvent.KEYCODE_DPAD_UP -> android.view.View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> android.view.View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> android.view.View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.View.FOCUS_RIGHT
                    else -> null
                }
                direction?.let { move ->
                    window.currentFocus?.focusSearch(move)?.let { next ->
                        if(next.requestFocus(move)) next.requestRectangleOnScreen(android.graphics.Rect(0,0,next.width,next.height),true)
                    }
                }
            }
        }
    }
    fun clear() { handler.removeCallbacks(repeat);state.clear();target=null }
}
