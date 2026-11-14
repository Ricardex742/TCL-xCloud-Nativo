package com.xcloud.tclnative.webrtc

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/** Bounded observations even in menus. No serial numbers or credentials; synthetic local actions excluded. */
object AndroidControllerMonitor {
    data class Snapshot(val down: Long=0,val up: Long=0,val moves: Long=0,val name: String="",val state: GamepadState=GamepadState())
    private var value=Snapshot()
    private var last=""
    private var lastMotion=Long.MIN_VALUE
    @Synchronized fun clear() { value=Snapshot();last="";lastMotion=Long.MIN_VALUE }
    @Synchronized fun snapshot()=value
    private fun physical(id: Int,source: Int)=id>=0 &&
        (source and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD || source and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK)
    @Synchronized fun key(event: KeyEvent) {
        if(!physical(event.deviceId,event.source) || event.action !in setOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) return
        val token="${event.deviceId}:${event.eventTime}:${event.action}:${event.keyCode}:${event.repeatCount}"
        if(last==token) return
        last=token
        val down=event.action==KeyEvent.ACTION_DOWN
        val mask=when(event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> 16;KeyEvent.KEYCODE_BUTTON_B -> 32;KeyEvent.KEYCODE_BUTTON_X -> 64;KeyEvent.KEYCODE_BUTTON_Y -> 128
            KeyEvent.KEYCODE_BUTTON_START -> 4;KeyEvent.KEYCODE_BUTTON_SELECT -> 8;KeyEvent.KEYCODE_BUTTON_MODE -> 2
            KeyEvent.KEYCODE_DPAD_UP -> 256;KeyEvent.KEYCODE_DPAD_DOWN -> 512;KeyEvent.KEYCODE_DPAD_LEFT -> 1024;KeyEvent.KEYCODE_DPAD_RIGHT -> 2048
            KeyEvent.KEYCODE_BUTTON_L1 -> 4096;KeyEvent.KEYCODE_BUTTON_R1 -> 8192;KeyEvent.KEYCODE_BUTTON_THUMBL -> 16384;KeyEvent.KEYCODE_BUTTON_THUMBR -> 32768
            else -> 0
        }
        value=value.copy(down=value.down+if(down) 1 else 0,up=value.up+if(down) 0 else 1,
            name=event.device?.name?.take(80) ?: "Android ${event.deviceId}",
            state=value.state.copy(buttons=if(down) value.state.buttons or mask else value.state.buttons and mask.inv()))
    }
    @Synchronized fun motion(event: MotionEvent) {
        if(!physical(event.deviceId,event.source) || event.action!=MotionEvent.ACTION_MOVE) return
        value=value.copy(moves=value.moves+1)
        // Diagnostics do not need to resample every axis at the physical controller's full report rate.
        if(lastMotion!=Long.MIN_VALUE && event.eventTime-lastMotion in 0..49) return
        lastMotion=event.eventTime
        fun axis(id: Int)=event.getAxisValue(id)
        value=value.copy(name=event.device?.name?.take(80) ?: "Android ${event.deviceId}",
            state=value.state.copy(leftX=axis(MotionEvent.AXIS_X),leftY=axis(MotionEvent.AXIS_Y),
                rightX=axis(MotionEvent.AXIS_Z),rightY=axis(MotionEvent.AXIS_RZ),
                leftTrigger=maxOf(axis(MotionEvent.AXIS_LTRIGGER),axis(MotionEvent.AXIS_BRAKE)),
                rightTrigger=maxOf(axis(MotionEvent.AXIS_RTRIGGER),axis(MotionEvent.AXIS_GAS))))
    }
}
