package com.xcloud.tclnative.webrtc

import android.view.KeyEvent

/** Local menu actions, not Android system input. Repeat applies only to directions. */
internal class ControllerNavigation {
    data class Stroke(val key: Int,val down: Boolean,val repeat: Int=0)
    private var horizontal=0;private var vertical=0
    private var held=emptySet<Int>()
    private val repeats=mutableMapOf<Int,Pair<Long,Int>>()
    private fun direction(value: Float,old: Int): Int = when {
        value<=-.60f -> -1;value>=.60f -> 1
        old<0 && value<-.35f -> -1;old>0 && value>.35f -> 1;else -> 0
    }
    fun update(state: GamepadState,now: Long): List<Stroke> {
        horizontal=direction(state.leftX,horizontal);vertical=direction(state.leftY,vertical)
        val next=linkedSetOf<Int>()
        mapping.forEach { (mask,key) -> if(state.buttons and mask!=0) next+=key }
        if(horizontal<0) next+=KeyEvent.KEYCODE_DPAD_LEFT
        if(horizontal>0) next+=KeyEvent.KEYCODE_DPAD_RIGHT
        if(vertical<0) next+=KeyEvent.KEYCODE_DPAD_UP
        if(vertical>0) next+=KeyEvent.KEYCODE_DPAD_DOWN
        val strokes=(held-next).map { Stroke(it,false) }+(next-held).map { Stroke(it,true) }
        (held-next).forEach(repeats::remove)
        (next-held).filter { it in directions }.forEach { repeats[it]=(now+350) to 0 }
        held=next
        return strokes
    }
    fun repeat(now: Long): List<Stroke> = repeats.toMap().mapNotNull { (key,value) ->
        if(now<value.first) null else {
            val count=value.second+1;repeats[key]=(now+90) to count;Stroke(key,true,count)
        }
    }
    fun clear() { held=emptySet();repeats.clear();horizontal=0;vertical=0 }
    val repeating get()=repeats.isNotEmpty()
    companion object {
        private val directions=setOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT)
        private val mapping=linkedMapOf(256 to KeyEvent.KEYCODE_DPAD_UP,512 to KeyEvent.KEYCODE_DPAD_DOWN,
            1024 to KeyEvent.KEYCODE_DPAD_LEFT,2048 to KeyEvent.KEYCODE_DPAD_RIGHT,16 to KeyEvent.KEYCODE_BUTTON_A,
            32 to KeyEvent.KEYCODE_BUTTON_B,64 to KeyEvent.KEYCODE_BUTTON_X,128 to KeyEvent.KEYCODE_BUTTON_Y,
            4096 to KeyEvent.KEYCODE_BUTTON_L1,8192 to KeyEvent.KEYCODE_BUTTON_R1,4 to KeyEvent.KEYCODE_BUTTON_START,
            8 to KeyEvent.KEYCODE_BUTTON_SELECT,2 to KeyEvent.KEYCODE_BUTTON_MODE)
    }
}
