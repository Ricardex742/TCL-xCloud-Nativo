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
    fun hit(x: Float,y: Float): TouchControl? {
        var closest: TouchControl?=null;var distance=Float.POSITIVE_INFINITY
        // A large overlapping button must not steal a tap on another button's center.
        for(index in controls.indices.reversed()) {
            val c=controls[index]
            if(c.kind==TouchKind.CAMERA || !inside(c,x,y)) continue
            val squared=(x-c.x)*(x-c.x)+(y-c.y)*(y-c.y)
            if(squared<distance) { closest=c;distance=squared }
        }
        return closest ?: controls.firstOrNull { it.kind==TouchKind.CAMERA && x>=width*.36f && x<=width*.985f && y>=height*.18f && y<=height*.95f }
    }
    fun down(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000,receivedAt: Long=now): TouchControl? {
        val control=hit(x,y) ?: return null
        if(control.kind in setOf(TouchKind.LEFT_STICK,TouchKind.RIGHT_STICK,TouchKind.DPAD,TouchKind.CAMERA) &&
            contacts.values.any { it.control.id==control.id }) return null
        releases.removeAll { it.id==control.id }
        contacts[id]=Contact(control,x,y,now,actionAt=receivedAt)
        return control
    }
    fun move(id: Int,x: Float,y: Float,now: Long=System.nanoTime()/1_000_000,receivedAt: Long=now) {
        val c=contacts[id] ?: return
        val dx=x-c.x; val dy=y-c.y
        if((c.control.kind==TouchKind.CAMERA || c.control.dragLook) && (dx!=0f || dy!=0f)) {
            val dt=(now-c.time).coerceIn(1,64)
            val response=if(preset.family==HudPreset.CALL_OF_DUTY) 1.6f else 1f
            val multiplier=response*settings.sensitivity.coerceIn(40,1200)/100f*3f/(height.coerceAtLeast(1)*.0018f*dt)
            val vx=dx*multiplier; val vy=dy*multiplier
            val magnitude=hypot(vx,vy)
            if(magnitude>0f) {
                val dz=settings.lookDeadzone.coerceIn(0,35)/100f
                val strength=(dz+(1-dz)*magnitude.coerceAtMost(1f).pow(.75f)).coerceAtMost(1f)
                c.lookX=vx/magnitude*strength; c.lookY=vy/magnitude*strength; c.lookAt=receivedAt
            }
        }
        if(c.control.gestures.isNotEmpty()) {
            val totalX=x-c.startX; val totalY=y-c.startY
            // A small hysteresis keeps the selected action stable while the finger is held.
            val threshold=maxOf(height*.018f,c.control.radius*.30f)
            if(hypot(totalX,totalY)>threshold) {
                val direction=if(abs(totalX)>abs(totalY)) { if(totalX<0) HudSwipe.LEFT else HudSwipe.RIGHT }
                    else if(totalY<0) HudSwipe.UP else HudSwipe.DOWN
                if(direction in c.control.gestures && direction!=c.gesture) { c.gesture=direction; c.actionAt=receivedAt }
            }
        }
        c.x=x; c.y=y; c.time=now
    }
    private fun action(c: Contact)=c.control.gestures[c.gesture]?.action ?: c.control.action
    fun gestureLabel(control: TouchControl): String?=contacts.values.firstOrNull { it.control.id==control.id }
        ?.let { it.control.gestures[it.gesture]?.label }
    fun up(id: Int,now: Long=System.nanoTime()/1_000_000,receivedAt: Long=now) {
        val contact=contacts.remove(id) ?: return
        val control=contact.control
        val chosen=action(contact)
        val settle=when {
            control.gestures.isNotEmpty() -> GESTURE_SETTLE_MS
            preset.football && control.id in FOOTBALL_TAP_IDS && chosen!=null && chosen.buttons!=0 -> FOOTBALL_TAP_MS
            else -> 0L
        }
        // Delivery time matters when Android drains old DOWN/UP events in one batch.
        // Keep only the action the user actually pressed; never replay a tap sequence.
        val until=contact.actionAt+settle
        if(chosen!=null && settle>0 && until>receivedAt) releases.add(Release(control.id,chosen,until))
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
    /** ACTION_DOWN begins a new Android gesture: old fingers can no longer be held. */
    fun startGesture(): Int = contacts.size.also { contacts.clear() }
    fun clear() { contacts.clear(); releases.clear() }
    private fun sprinting(contact: Contact,sprint: TouchControl?): Boolean {
        if(!settings.sprintGesture || preset.family!=HudPreset.CALL_OF_DUTY ||
            contact.control.kind!=TouchKind.LEFT_STICK || sprint==null) return false
        val x=contact.x-contact.control.x;val y=contact.y-contact.control.y
        val distance=hypot(x,y)
        if(distance<contact.control.radius*1.35f || y>=0) return false
        val tx=sprint.x-contact.control.x;val ty=sprint.y-contact.control.y
        val target=hypot(tx,ty)
        return target>0 && (x*tx+y*ty)/(distance*target)>=.85f
    }
    fun held(control: TouchControl)=contacts.values.any { it.control.id==control.id ||
        control.id=="sprint" && sprinting(it,control) } || releases.any { it.id==control.id }
    fun nextLookReset(now: Long): Long? {
        var latest=Long.MIN_VALUE
        for(c in contacts.values) if(c.lookAt!=Long.MIN_VALUE && c.lookAt+LOOK_IDLE_MS>now) latest=maxOf(latest,c.lookAt)
        return if(latest==Long.MIN_VALUE) null else latest+LOOK_IDLE_MS
    }
    fun nextUpdate(now: Long): Long? {
        var next=nextLookReset(now)
        for(release in releases) if(release.until>now && (next==null || release.until<next)) next=release.until
        return next
    }
    fun state(now: Long=System.nanoTime()/1_000_000): GamepadState {
        releases.removeAll { it.until<=now }
        var buttons=0;var leftX=0f;var leftY=0f;var rightX=0f;var rightY=0f
        var leftTrigger=0f;var rightTrigger=0f;var steering=0f
        var look: Contact?=null
        fun combine(action: HudAction?) {
            if(action==null) return
            buttons=buttons or action.buttons
            if(HudBinding.LT in action.keys) leftTrigger=1f
            if(HudBinding.RT in action.keys) rightTrigger=1f
            if(HudBinding.STEER_LEFT in action.keys) steering-=1f
            if(HudBinding.STEER_RIGHT in action.keys) steering+=1f
        }
        val sprint=if(preset.family==HudPreset.CALL_OF_DUTY && settings.sprintGesture) controls.firstOrNull { it.id=="sprint" } else null
        for(contact in contacts.values) {
            val control=contact.control
            val dx=(contact.x-control.x)/control.radius;val dy=(contact.y-control.y)/control.radius
            val length=maxOf(1f,hypot(dx,dy))
            when(control.kind) {
                TouchKind.LEFT_STICK -> { leftX=dx/length;leftY=dy/length;if(sprinting(contact,sprint)) combine(sprint?.action) }
                TouchKind.RIGHT_STICK -> { rightX=dx/length;rightY=dy/length }
                TouchKind.DPAD -> buttons=buttons or
                    (if(dx<-.25f) 1024 else if(dx>.25f) 2048 else 0) or
                    (if(dy<-.25f) 256 else if(dy>.25f) 512 else 0)
                TouchKind.CAMERA -> Unit
                else -> combine(action(contact))
            }
            if(contact.lookAt!=Long.MIN_VALUE && now-contact.lookAt<LOOK_IDLE_MS &&
                (look==null || contact.lookAt>look.lookAt)) look=contact
        }
        for(release in releases) combine(release.action)
        if(steering!=0f) leftX=steering.coerceIn(-1f,1f)
        if(look!=null) { rightX=look.lookX;rightY=look.lookY }
        return GamepadState(buttons,leftX,leftY,rightX,rightY,leftTrigger,rightTrigger)
    }
    companion object {
        const val LOOK_IDLE_MS=32L;const val GESTURE_SETTLE_MS=64L;const val FOOTBALL_TAP_MS=32L
        private val FOOTBALL_TAP_IDS=setOf("pass","shoot","through","cross")
    }
}
