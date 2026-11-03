package com.xcloud.tclnative.webrtc

import kotlin.math.hypot

enum class TouchKind { BUTTON, LEFT_STICK, RIGHT_STICK, DPAD, LEFT_TRIGGER, RIGHT_TRIGGER }
data class TouchControl(val label: String, val x: Float, val y: Float, val radius: Float,
                        val kind: TouchKind, val button: Int = 0)

/** Fixed, multi-finger gamepad. Releasing one finger never releases another finger's held input. */
class VirtualGamepad(width: Int,height: Int) {
    private data class Contact(val control: TouchControl,var x: Float,var y: Float)
    private val contacts=mutableMapOf<Int,Contact>()
    val controls: List<TouchControl> = buildList {
        fun button(label: String,x: Float,y: Float,mask: Int,kind: TouchKind=TouchKind.BUTTON) {
            add(TouchControl(label,x*width,y*height,height*.046f,kind,mask))
        }
        button("A",.88f,.81f,16); button("B",.935f,.70f,32)
        button("X",.825f,.70f,64); button("Y",.88f,.59f,128)
        button("LB",.10f,.15f,4096); button("RB",.90f,.15f,8192)
        button("LT",.10f,.29f,0,TouchKind.LEFT_TRIGGER); button("RT",.90f,.29f,0,TouchKind.RIGHT_TRIGGER)
        button("L3",.30f,.49f,16384); button("R3",.72f,.49f,32768)
        button("☰",.55f,.15f,4); button("⧉",.45f,.15f,8)
        add(TouchControl("L",width*.18f,height*.73f,height*.12f,TouchKind.LEFT_STICK))
        add(TouchControl("R",width*.68f,height*.73f,height*.12f,TouchKind.RIGHT_STICK))
        add(TouchControl("+",width*.35f,height*.75f,height*.080f,TouchKind.DPAD))
    }
    fun down(id: Int,x: Float,y: Float) {
        val control=controls.firstOrNull { hypot(x-it.x,y-it.y)<=it.radius } ?: return
        if(control.kind in setOf(TouchKind.LEFT_STICK,TouchKind.RIGHT_STICK,TouchKind.DPAD) && contacts.values.any { it.control==control }) return
        contacts[id]=Contact(control,x,y)
    }
    fun move(id: Int,x: Float,y: Float) { contacts[id]?.let { it.x=x; it.y=y } }
    fun up(id: Int) { contacts.remove(id) }
    fun clear() { contacts.clear() }
    fun held(control: TouchControl) = contacts.values.any { it.control==control }
    fun state(): GamepadState {
        var state=GamepadState()
        contacts.values.forEach { c ->
            val dx=(c.x-c.control.x)/c.control.radius
            val dy=(c.y-c.control.y)/c.control.radius
            val length=maxOf(1f,hypot(dx,dy))
            when(c.control.kind) {
                TouchKind.BUTTON -> state=state.copy(buttons=state.buttons or c.control.button)
                TouchKind.LEFT_TRIGGER -> state=state.copy(leftTrigger=1f)
                TouchKind.RIGHT_TRIGGER -> state=state.copy(rightTrigger=1f)
                TouchKind.LEFT_STICK -> state=state.copy(leftX=dx/length,leftY=dy/length)
                TouchKind.RIGHT_STICK -> state=state.copy(rightX=dx/length,rightY=dy/length)
                TouchKind.DPAD -> state=state.copy(buttons=state.buttons or
                    (if(dx<-.25f) 1024 else if(dx>.25f) 2048 else 0) or
                    (if(dy<-.25f) 256 else if(dy>.25f) 512 else 0))
            }
        }
        return state
    }
}
