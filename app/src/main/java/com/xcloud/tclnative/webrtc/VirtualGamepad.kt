package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.touch.*
import kotlin.math.hypot

enum class TouchKind { BUTTON, LEFT_STICK, RIGHT_STICK, DPAD, LEFT_TRIGGER, RIGHT_TRIGGER, CAMERA }
data class TouchControl(val label: String,val x: Float,val y: Float,val radius: Float,
                        val kind: TouchKind,val button: Int=0,val id: String=label,
                        val binding: HudBinding?=null,val icon: HudIcon=HudIcon.TEXT,
                        val shape: HudShape=HudShape.CIRCLE,val dragLook: Boolean=false,val page: HudPreset?=null)

/** Multi-finger controller with game-specific layouts. Camera swipes emulate the right stick. */
class VirtualGamepad(private val width: Int,private val height: Int,
                     preset: HudPreset=HudPreset.GAMEPAD,private val settings: HudSettings=HudSettings()) {
    private data class Contact(var control: TouchControl,var x: Float,var y: Float,var time: Long,
                               var lookX: Float=0f,var lookY: Float=0f,var lookAt: Long=Long.MIN_VALUE)
    private val contacts=mutableMapOf<Int,Contact>()
    var preset=preset
        private set
    var controls=layout(preset)
        private set
    private fun layout(preset: HudPreset)=GameHud.elements(preset).map { element ->
        val custom=settings.placements[element.id]
        val r=(height*element.radius*settings.scale.coerceIn(75,150)/100f*(custom?.scale ?: 1f))
            .coerceAtLeast(1f).coerceAtMost(minOf(width,height)*.45f)
        val binding=custom?.binding ?: element.binding
        TouchControl(element.label,((custom?.x ?: element.x)*width).coerceIn(r,width-r),
            ((custom?.y ?: element.y)*height).coerceIn(r,height-r),r,element.kind,binding?.button ?: 0,
            element.id,binding,element.icon,element.shape,element.dragLook,element.page)
    }
    fun hit(x: Float,y: Float): TouchControl? = controls.asReversed().firstOrNull {
        it.kind!=TouchKind.CAMERA && hypot(x-it.x,y-it.y)<=it.radius
    } ?: controls.firstOrNull { it.kind==TouchKind.CAMERA && x>=width*.36f && x<=width*.985f && y>=height*.18f && y<=height*.95f }
    fun down(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000) {
        val control=hit(x,y) ?: return
        if(control.kind in setOf(TouchKind.LEFT_STICK,TouchKind.RIGHT_STICK,TouchKind.DPAD,TouchKind.CAMERA) &&
            contacts.values.any { it.control.id==control.id }) return
        contacts[id]=Contact(control,x,y,now)
    }
    fun move(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000) {
        val contact=contacts[id] ?: return
        val dx=x-contact.x; val dy=y-contact.y
        if((contact.control.kind==TouchKind.CAMERA || contact.control.dragLook) && (dx!=0f || dy!=0f)) {
            val dt=(now-contact.time).coerceIn(4,50)
            val multiplier=settings.sensitivity.coerceIn(40,220)/100f/(height.coerceAtLeast(1)*.0018f*dt)
            contact.lookX=(dx*multiplier).coerceIn(-1f,1f)
            contact.lookY=(dy*multiplier).coerceIn(-1f,1f)
            contact.lookAt=now
        }
        contact.x=x; contact.y=y; contact.time=now
    }
    fun up(id: Int) {
        val contact=contacts.remove(id) ?: return
        contact.control.page?.takeIf { hypot(contact.x-contact.control.x,contact.y-contact.control.y)<=contact.control.radius*1.4f }
            ?.let(::setPage)
    }
    /** Shared move/look controls survive the Fortnite combat/build page switch. */
    fun setPage(next: HudPreset) {
        preset=next; controls=layout(next)
        val nextById=controls.associateBy { it.id }
        contacts.entries.removeAll { (_,contact) ->
            val control=nextById[contact.control.id]
            if(control==null || control.binding!=contact.control.binding) true else { contact.control=control; false }
        }
    }
    fun clear() { contacts.clear() }
    fun held(control: TouchControl)=contacts.values.any { it.control.id==control.id }
    fun nextLookReset(now: Long): Long? = contacts.values.map { it.lookAt }.filter {
        it!=Long.MIN_VALUE && it+LOOK_IDLE_MS>now
    }.maxOrNull()?.plus(LOOK_IDLE_MS)
    fun state(now: Long=System.nanoTime()/1_000_000): GamepadState {
        var state=GamepadState()
        var steering=0f
        contacts.values.forEach { contact ->
            val control=contact.control
            val dx=(contact.x-control.x)/control.radius
            val dy=(contact.y-control.y)/control.radius
            val length=maxOf(1f,hypot(dx,dy))
            when(control.kind) {
                TouchKind.LEFT_STICK -> state=state.copy(leftX=dx/length,leftY=dy/length)
                TouchKind.RIGHT_STICK -> state=state.copy(rightX=dx/length,rightY=dy/length)
                TouchKind.DPAD -> state=state.copy(buttons=state.buttons or
                    (if(dx<-.25f) 1024 else if(dx>.25f) 2048 else 0) or
                    (if(dy<-.25f) 256 else if(dy>.25f) 512 else 0))
                TouchKind.CAMERA -> Unit
                else -> when(control.binding) {
                    HudBinding.LT -> state=state.copy(leftTrigger=1f)
                    HudBinding.RT -> state=state.copy(rightTrigger=1f)
                    HudBinding.STEER_LEFT -> steering-=1f
                    HudBinding.STEER_RIGHT -> steering+=1f
                    else -> state=state.copy(buttons=state.buttons or control.button)
                }
            }
        }
        if(steering!=0f) state=state.copy(leftX=steering.coerceIn(-1f,1f))
        val look=contacts.values.filter { it.lookAt!=Long.MIN_VALUE && now-it.lookAt<LOOK_IDLE_MS }
            .maxByOrNull { it.lookAt }
        if(look!=null) state=state.copy(rightX=look.lookX,rightY=look.lookY)
        return state
    }
    companion object { const val LOOK_IDLE_MS=80L }
}
