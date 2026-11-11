package com.xcloud.tclnative.webrtc

import android.view.KeyEvent
import java.util.ArrayDeque

data class NativeKey(val code: Int,val pressed: Boolean)
data class NativeMouse(val x: Int=0,val y: Int=0,val wheelX: Int=0,val wheelY: Int=0,val buttons: Int=0)

/** Bounded reliable transitions, sharing the gamepad sequence and send thread. */
class DesktopInputBuffer {
    private val keys=ArrayDeque<NativeKey>()
    private val mice=ArrayDeque<NativeMouse>()
    private val held=mutableSetOf<Int>()
    private val reported=mutableSetOf<Int>()
    private val empty=emptyList<NativeKey>() to emptyList<NativeMouse>()
    @Synchronized fun key(key: NativeKey) {
        if(key.code !in 1..255 || (key.pressed && key.code in held) || (!key.pressed && key.code !in held)) return
        if(keys.size>=64) { keys.clear();(reported+held).forEach { keys.addLast(NativeKey(it,false)) };held.clear() }
        if(key.pressed) { if(held.size>=32) return;held.add(key.code) } else held.remove(key.code)
        keys.addLast(key)
    }
    @Synchronized fun mouse(mouse: NativeMouse) {
        val last=mice.peekLast()
        if(last!=null && last.buttons==mouse.buttons) {
            fun sum(a: Int,b: Int)=(a.toLong()+b).coerceIn(-32767,32767).toInt()
            mice.removeLast();mice.addLast(mouse.copy(x=sum(last.x,mouse.x),y=sum(last.y,mouse.y),
                wheelX=sum(last.wheelX,mouse.wheelX),wheelY=sum(last.wheelY,mouse.wheelY)))
        } else {
            if(mice.size>=32) { mice.clear();mice.addLast(NativeMouse()) }
            mice.addLast(mouse)
        }
    }
    @Synchronized fun pending()=keys.isNotEmpty() || mice.isNotEmpty()
    @Synchronized fun next(): Pair<List<NativeKey>,List<NativeMouse>> {
        if(keys.isEmpty() && mice.isEmpty()) return empty
        val k=mutableListOf<NativeKey>();val m=mutableListOf<NativeMouse>()
        repeat(minOf(keys.size,16)) {
            val key=keys.removeFirst();k.add(key)
            if(key.pressed) reported.add(key.code) else reported.remove(key.code)
        }
        repeat(minOf(mice.size,8)) { m.add(mice.removeFirst()) }
        return k to m
    }
    @Synchronized fun release() {
        keys.clear();(reported+held).forEach { keys.addLast(NativeKey(it,false)) };held.clear()
        mice.clear();mice.addLast(NativeMouse())
    }
}

object DesktopKeys {
    fun windowsKey(key: Int): Int? = when(key) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x41+key-KeyEvent.KEYCODE_A
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30+key-KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0x70+key-KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_SPACE -> 0x20;KeyEvent.KEYCODE_ENTER -> 0x0d;KeyEvent.KEYCODE_TAB -> 9
        KeyEvent.KEYCODE_ESCAPE -> 0x1b;KeyEvent.KEYCODE_DEL -> 8;KeyEvent.KEYCODE_FORWARD_DEL -> 0x2e
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xa0;KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xa1
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xa2;KeyEvent.KEYCODE_CTRL_RIGHT -> 0xa3
        KeyEvent.KEYCODE_ALT_LEFT -> 0xa4;KeyEvent.KEYCODE_ALT_RIGHT -> 0xa5
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25;KeyEvent.KEYCODE_DPAD_UP -> 0x26;KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27;KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        KeyEvent.KEYCODE_PAGE_UP -> 0x21;KeyEvent.KEYCODE_PAGE_DOWN -> 0x22;KeyEvent.KEYCODE_MOVE_HOME -> 0x24;KeyEvent.KEYCODE_MOVE_END -> 0x23
        KeyEvent.KEYCODE_INSERT -> 0x2d;KeyEvent.KEYCODE_CAPS_LOCK -> 0x14
        KeyEvent.KEYCODE_MINUS -> 0xbd;KeyEvent.KEYCODE_EQUALS -> 0xbb;KeyEvent.KEYCODE_LEFT_BRACKET -> 0xdb;KeyEvent.KEYCODE_RIGHT_BRACKET -> 0xdd
        KeyEvent.KEYCODE_SEMICOLON -> 0xba;KeyEvent.KEYCODE_APOSTROPHE -> 0xde;KeyEvent.KEYCODE_COMMA -> 0xbc;KeyEvent.KEYCODE_PERIOD -> 0xbe
        KeyEvent.KEYCODE_SLASH -> 0xbf;KeyEvent.KEYCODE_BACKSLASH -> 0xdc;KeyEvent.KEYCODE_GRAVE -> 0xc0
        else -> null
    }
    fun nativeSupported(types: Set<String>)=types.any { it.equals("MKB",true) || it.contains("mouse",true) || it.contains("keyboard",true) }
}

/** An explicit controller emulation, not native mouse aiming. */
class DesktopGamepad {
    private val held=mutableSetOf<Int>()
    private var buttons=0
    private var x=0f;private var y=0f
    fun key(key: Int,down: Boolean): Boolean {
        if(key !in setOf(KeyEvent.KEYCODE_W,KeyEvent.KEYCODE_A,KeyEvent.KEYCODE_S,KeyEvent.KEYCODE_D,KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_CTRL_LEFT,KeyEvent.KEYCODE_R,KeyEvent.KEYCODE_E,KeyEvent.KEYCODE_Q,KeyEvent.KEYCODE_SHIFT_LEFT,KeyEvent.KEYCODE_TAB,KeyEvent.KEYCODE_ESCAPE)) return false
        if(down) held.add(key) else held.remove(key)
        return true
    }
    fun mouse(dx: Float,dy: Float,buttons: Int,sensitivity: Int) {
        this.buttons=buttons
        x=(dx*sensitivity.coerceIn(25,300)/5000f).coerceIn(-1f,1f)
        y=(dy*sensitivity.coerceIn(25,300)/5000f).coerceIn(-1f,1f)
    }
    fun releaseMotion() { x=0f;y=0f }
    fun reset() { held.clear();buttons=0;releaseMotion() }
    fun state(): GamepadState {
        fun pressed(key: Int)=key in held
        var mask=0
        for((key,bit) in listOf(KeyEvent.KEYCODE_SPACE to 16,KeyEvent.KEYCODE_CTRL_LEFT to 32,KeyEvent.KEYCODE_R to 64,
            KeyEvent.KEYCODE_E to 64,KeyEvent.KEYCODE_Q to 128,KeyEvent.KEYCODE_SHIFT_LEFT to 16384,KeyEvent.KEYCODE_TAB to 8,KeyEvent.KEYCODE_ESCAPE to 4))
            if(pressed(key)) mask=mask or bit
        if(buttons and 4!=0) mask=mask or 32768
        return GamepadState(mask,(if(pressed(KeyEvent.KEYCODE_D)) 1f else 0f)-(if(pressed(KeyEvent.KEYCODE_A)) 1f else 0f),
            (if(pressed(KeyEvent.KEYCODE_S)) 1f else 0f)-(if(pressed(KeyEvent.KEYCODE_W)) 1f else 0f),x,y,
            if(buttons and 2!=0) 1f else 0f,if(buttons and 1!=0) 1f else 0f)
    }
}
