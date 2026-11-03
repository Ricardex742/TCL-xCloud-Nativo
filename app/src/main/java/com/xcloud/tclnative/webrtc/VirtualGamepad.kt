package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.touch.*
import kotlin.math.*

enum class TouchKind { BUTTON, LEFT_STICK, RIGHT_STICK, DPAD, LEFT_TRIGGER, RIGHT_TRIGGER, CAMERA }
data class TouchControl(val label: String,val x: Float,val y: Float,val radius: Float,
                        val kind: TouchKind,val button: Int=0,val id: String=label,
                        val binding: HudBinding?=null,val icon: HudIcon=HudIcon.TEXT,
                        val shape: HudShape=HudShape.CIRCLE,val dragLook: Boolean=false,val page: HudPreset?=null,
                        val action: HudAction?=binding?.let { HudAction(it) },
                        val gestures: Map<HudSwipe,HudGesture> = emptyMap())

/** No game-state inference: context pages are switched explicitly, gestures combine simultaneous inputs. */
class VirtualGamepad(private val width: Int,private val height: Int,
                     preset: HudPreset=HudPreset.GAMEPAD,private val settings: HudSettings=HudSettings()) {
    private data class Contact(var control: TouchControl,var x: Float,var y: Float,var time: Long,
                               val startX: Float=x,val startY: Float=y,
                               var lookX: Float=0f,var lookY: Float=0f,var lookAt: Long=Long.MIN_VALUE,
                               var gesture: HudSwipe?=null,var actionAt: Long=time)
    private data class Release(val id: String,val action: HudAction,val until: Long)
    private val contacts=mutableMapOf<Int,Contact>()
    private val releases=mutableListOf<Release>()
    var preset=preset
        private set
    var controls=layout(preset)
        private set
    private fun layout(preset: HudPreset)=GameHud.elements(preset,settings).map { element ->
        val custom=settings.placements[element.id]
        val r=(height*element.radius*settings.scale.coerceIn(75,150)/100f*(custom?.scale ?: 1f))
            .coerceAtLeast(1f).coerceAtMost(if(element.id=="football-mode") minOf(width/3.6f,height*.45f) else minOf(width,height)*.45f)
        val paddingX=if(element.id=="football-mode") r*1.8f else r
        val binding=custom?.binding ?: element.binding
        val action=custom?.action ?: custom?.binding?.let { HudAction(it) } ?: element.action
        // A legacy single-button remap also follows through to default modifier gestures.
        val gestures=custom?.gestures ?: element.gestures.mapValues { (_,gesture) ->
            if(action!=null && element.action!=null) gesture.copy(action=HudAction((gesture.action.keys-element.action.keys)+action.keys))
            else gesture
        }
        TouchControl(element.label,((custom?.x ?: element.x)*width).coerceIn(paddingX,width-paddingX),
            ((custom?.y ?: element.y)*height).coerceIn(r,height-r),r,element.kind,action?.buttons ?: 0,
            element.id,binding,element.icon,element.shape,element.dragLook,element.page,action,gestures)
    }
    private fun inside(c: TouchControl,x: Float,y: Float,slack: Float=1f): Boolean {
        val dx=abs(x-c.x); val dy=abs(y-c.y); val r=c.radius*slack
        return when {
            c.id=="football-mode" -> dx<=r*1.8f && dy<=r*.48f
            c.shape==HudShape.SQUARE -> dx<=r && dy<=r
            c.shape==HudShape.PEDAL -> dx<=r*.72f && dy<=r
            else -> hypot(dx,dy)<=r
        }
    }
    fun hit(x: Float,y: Float): TouchControl? = controls.asReversed().firstOrNull {
        it.kind!=TouchKind.CAMERA && inside(it,x,y)
    } ?: controls.firstOrNull { it.kind==TouchKind.CAMERA && x>=width*.36f && x<=width*.985f && y>=height*.18f && y<=height*.95f }
    fun down(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000) {
        val control=hit(x,y) ?: return
        if(control.kind in setOf(TouchKind.LEFT_STICK,TouchKind.RIGHT_STICK,TouchKind.DPAD,TouchKind.CAMERA) &&
            contacts.values.any { it.control.id==control.id }) return
        releases.removeAll { it.id==control.id }
        contacts[id]=Contact(control,x,y,now)
    }
    fun move(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000) {
        val c=contacts[id] ?: return
        val dx=x-c.x; val dy=y-c.y
        if((c.control.kind==TouchKind.CAMERA || c.control.dragLook) && (dx!=0f || dy!=0f)) {
            val dt=(now-c.time).coerceIn(1,64)
            val multiplier=settings.sensitivity.coerceIn(40,1200)/100f*3f/(height.coerceAtLeast(1)*.0018f*dt)
            val vx=dx*multiplier; val vy=dy*multiplier
            val magnitude=hypot(vx,vy)
            if(magnitude>0f) {
                val dz=settings.lookDeadzone.coerceIn(0,35)/100f
                val strength=(dz+(1-dz)*magnitude.coerceAtMost(1f).pow(.75f)).coerceAtMost(1f)
                c.lookX=vx/magnitude*strength; c.lookY=vy/magnitude*strength; c.lookAt=now
            }
        }
        if(c.control.gestures.isNotEmpty()) {
            val totalX=x-c.startX; val totalY=y-c.startY
            // A small hysteresis keeps the selected action stable while the finger is held.
            val threshold=maxOf(height*.018f,c.control.radius*.30f)
            if(hypot(totalX,totalY)>threshold) {
                val direction=if(abs(totalX)>abs(totalY)) { if(totalX<0) HudSwipe.LEFT else HudSwipe.RIGHT }
                    else if(totalY<0) HudSwipe.UP else HudSwipe.DOWN
                if(direction in c.control.gestures && direction!=c.gesture) { c.gesture=direction; c.actionAt=now }
            }
        }
        c.x=x; c.y=y; c.time=now
    }
    private fun action(c: Contact)=c.control.gestures[c.gesture]?.action ?: c.control.action
    fun gestureLabel(control: TouchControl): String?=contacts.values.firstOrNull { it.control.id==control.id }
        ?.let { it.control.gestures[it.gesture]?.label }
    fun up(id: Int,now: Long=System.nanoTime()/1_000_000) {
        val contact=contacts.remove(id) ?: return
        val control=contact.control
        // FC shoots on release; keep the chosen modifiers visible for at least a cloud game frame.
        // This only settles user-selected gestures, never repeats or generates a sequence of actions.
        if(control.gestures.isNotEmpty()) action(contact)?.let { chosen ->
            val until=contact.actionAt+GESTURE_SETTLE_MS
            if(until>now) releases.add(Release(control.id,chosen,until))
        }
        control.page?.takeIf { inside(control,contact.x,contact.y,1.3f) }?.let(::setPage)
    }
    /** Movement survives page changes; removed actions and pending gesture releases are cleared. */
    fun setPage(next: HudPreset) {
        preset=next; controls=layout(next); releases.clear()
        val nextById=controls.associateBy { it.id }
        contacts.entries.removeAll { (_,contact) ->
            val control=nextById[contact.control.id]
            if(control==null || control.action!=contact.control.action) true else { contact.control=control; false }
        }
    }
    fun clear() { contacts.clear(); releases.clear() }
    fun held(control: TouchControl)=contacts.values.any { it.control.id==control.id } || releases.any { it.id==control.id }
    fun nextLookReset(now: Long): Long? = contacts.values.map { it.lookAt }.filter {
        it!=Long.MIN_VALUE && it+LOOK_IDLE_MS>now
    }.maxOrNull()?.plus(LOOK_IDLE_MS)
    fun nextUpdate(now: Long): Long?=listOfNotNull(nextLookReset(now),releases.filter { it.until>now }.minOfOrNull { it.until }).minOrNull()
    fun state(now: Long=System.nanoTime()/1_000_000): GamepadState {
        releases.removeAll { it.until<=now }
        var state=GamepadState(); var steering=0f
        fun combine(action: HudAction?) {
            if(action==null) return
            state=state.copy(buttons=state.buttons or action.buttons,
                leftTrigger=maxOf(state.leftTrigger,if(HudBinding.LT in action.keys) 1f else 0f),
                rightTrigger=maxOf(state.rightTrigger,if(HudBinding.RT in action.keys) 1f else 0f))
            if(HudBinding.STEER_LEFT in action.keys) steering-=1f
            if(HudBinding.STEER_RIGHT in action.keys) steering+=1f
        }
        contacts.values.forEach { contact ->
            val control=contact.control
            val dx=(contact.x-control.x)/control.radius; val dy=(contact.y-control.y)/control.radius
            val length=maxOf(1f,hypot(dx,dy))
            when(control.kind) {
                TouchKind.LEFT_STICK -> state=state.copy(leftX=dx/length,leftY=dy/length)
                TouchKind.RIGHT_STICK -> state=state.copy(rightX=dx/length,rightY=dy/length)
                TouchKind.DPAD -> state=state.copy(buttons=state.buttons or
                    (if(dx<-.25f) 1024 else if(dx>.25f) 2048 else 0) or
                    (if(dy<-.25f) 256 else if(dy>.25f) 512 else 0))
                TouchKind.CAMERA -> Unit
                else -> combine(action(contact))
            }
        }
        releases.forEach { combine(it.action) }
        if(steering!=0f) state=state.copy(leftX=steering.coerceIn(-1f,1f))
        val look=contacts.values.filter { it.lookAt!=Long.MIN_VALUE && now-it.lookAt<LOOK_IDLE_MS }.maxByOrNull { it.lookAt }
        if(look!=null) state=state.copy(rightX=look.lookX,rightY=look.lookY)
        return state
    }
    companion object { const val LOOK_IDLE_MS=32L; const val GESTURE_SETTLE_MS=64L }
}
