package com.xcloud.tclnative.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.webrtc.*
import kotlin.math.hypot

/** Native HUD rendered over the stream. Its virtual controls use the cloud gamepad input channel. */
class GameTouchView(context: Context,
    private val onGamepad: (GamepadState) -> Unit,
    private val onPointers: (List<NativePointer>) -> Unit,
    private val coordinates: (Float,Float) -> Pair<Float,Float>?,
    private val onMenu: () -> Unit
) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=DashboardTheme.font(context,true); textAlign=Paint.Align.CENTER }
    private val icons=HudIcons()
    private var automatic=HudPreset.GAMEPAD
    var configuration=HudSettings()
        private set
    private var model=VirtualGamepad(1,1)
    val preset: HudPreset get()=model.preset
    private var mode=TouchMode.OFF
    private val native=mutableMapOf<Int,NativePointer>()
    private var menuPointer: Int?=null
    var isEditing=false
        private set
    private var beforeEdit=configuration
    private var beforeEditPreset=HudPreset.GAMEPAD
    private var selected: String?=null
    private var editPointer: Int?=null
    private var dragX=0f; private var dragY=0f
    private var editAction: String?=null
    var onHudSaved: (HudSettings) -> Boolean = { true }
    var onBindingRequested: (String,HudBinding?) -> Unit = { _,_ -> }
    private val lookReset=Runnable { if(mode==TouchMode.VIRTUAL && !isEditing) publish() }
    init { visibility=GONE; isFocusable=false; contentDescription="HUD de toque e menu da partida" }
    fun configure(mode: TouchMode,game: GameTitle?=null,settings: HudSettings?=null) {
        releaseInputs(); isEditing=false
        if(game!=null) automatic=GameHud.automatic(game.titleId,game.name)
        if(settings!=null) configuration=settings
        this.mode=mode
        rebuild(configuration.preset ?: automatic)
        val menuOnly=game!=null && context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        visibility=if(mode==TouchMode.OFF && !menuOnly) GONE else VISIBLE
        invalidate()
    }
    fun applyHud(settings: HudSettings) {
        val oldPage=preset
        releaseInputs(); configuration=settings
        val resolved=settings.preset ?: automatic
        rebuild(if(resolved==HudPreset.FORTNITE && oldPage==HudPreset.FORTNITE_BUILD) oldPage else resolved); invalidate()
    }
    fun showPage(page: HudPreset) { releaseInputs(); model.setPage(page); invalidate() }
    private fun rebuild(page: HudPreset=preset) { model=VirtualGamepad(width.coerceAtLeast(1),height.coerceAtLeast(1),page,configuration) }
    fun beginEditing() {
        if(mode!=TouchMode.VIRTUAL) return
        releaseInputs(); beforeEdit=configuration; beforeEditPreset=preset; selected=null; isEditing=true; invalidate()
    }
    fun cancelEditing() {
        if(!isEditing) return
        releaseInputs(); configuration=beforeEdit; rebuild(beforeEditPreset); isEditing=false; invalidate()
    }
    fun setEditedBinding(id: String,binding: HudBinding) {
        if(!isEditing) return
        val control=model.controls.firstOrNull { it.id==id } ?: return
        placement(control) { it.copy(binding=binding) }
    }
    private fun placement(control: TouchControl,change: (HudPlacement) -> HudPlacement) {
        val old=configuration.placements[control.id] ?: HudPlacement(control.x/width,control.y/height)
        configuration=configuration.copy(placements=configuration.placements+(control.id to change(old)))
        rebuild(); invalidate()
    }
    fun releaseInputs() {
        removeCallbacks(lookReset)
        model.clear(); onGamepad(GamepadState())
        if(native.isNotEmpty()) onPointers(native.values.map { it.copy(type=2,pressure=0f) })
        native.clear(); menuPointer=null; editPointer=null; editAction=null; invalidate()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        val page=preset
        releaseInputs(); rebuild(page)
    }
    override fun onDetachedFromWindow() { releaseInputs(); super.onDetachedFromWindow() }
    private fun menuHit(x: Float,y: Float)=hypot(x-width*.50f,y-height*.055f)<=height*.038f
    private fun publish() {
        val now=SystemClock.uptimeMillis()
        onGamepad(model.state(now)); removeCallbacks(lookReset)
        model.nextLookReset(now)?.let { postDelayed(lookReset,(it-now).coerceAtLeast(1)) }
        postInvalidateOnAnimation()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(isEditing) return editTouch(event)
        val action=event.actionMasked
        val index=event.actionIndex
        val id=event.getPointerId(index)
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && menuPointer==null && menuHit(event.getX(index),event.getY(index))) {
            menuPointer=id; return true
        }
        if(action==MotionEvent.ACTION_CANCEL) { releaseInputs(); return true }
        if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && id==menuPointer) {
            menuPointer=null
            if(menuHit(event.getX(index),event.getY(index))) { releaseInputs(); performClick() }
            return true
        }
        if(mode==TouchMode.OFF) return false
        if(mode==TouchMode.NATIVE) {
            val events=mutableListOf<NativePointer>()
            fun pointer(i: Int,type: Int) {
                val pointerId=event.getPointerId(i)
                if(pointerId==menuPointer) return
                val position=coordinates(event.getX(i),event.getY(i))
                val old=native[pointerId]
                if(type==1 && (native.size>=10 || position==null)) return
                if(type!=1 && old==null) return
                val (x,y)=position ?: old?.let { it.x to it.y } ?: return
                val pressure=event.getPressure(i).let { if(it.isFinite() && it>0) it.coerceAtMost(1f) else 1f }
                val diameter=event.getTouchMajor(i).takeIf { it.isFinite() && it>0 } ?: 1f
                val p=NativePointer(pointerId,x,y,type,if(type==2) 0f else pressure,
                    diameter/width.coerceAtLeast(1),diameter/height.coerceAtLeast(1))
                if(type==2) native.remove(pointerId) else native[pointerId]=p
                events.add(p)
            }
            when(action) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> pointer(index,1)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> pointer(index,2)
                MotionEvent.ACTION_MOVE -> repeat(event.pointerCount) { pointer(it,3) }
            }
            if(events.isNotEmpty()) onPointers(events)
        } else {
            when(action) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> model.down(id,event.getX(index),event.getY(index),event.eventTime)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> model.up(id)
                MotionEvent.ACTION_MOVE -> repeat(event.pointerCount) { i ->
                    if(event.getPointerId(i)!=menuPointer) model.move(event.getPointerId(i),event.getX(i),event.getY(i),event.eventTime)
                }
            }
            publish()
        }
        return true
    }
    private fun editorAction(x: Float,y: Float): String? = when {
        y in height*.035f..height*.105f && x in width*.31f..width*.44f -> "cancel"
        y in height*.035f..height*.105f && x in width*.44f..width*.57f -> "reset"
        y in height*.035f..height*.105f && x in width*.57f..width*.70f -> "save"
        y in height*.90f..height*.985f && selected!=null && x in width*.33f..width*.43f -> "smaller"
        y in height*.90f..height*.985f && selected!=null && x in width*.43f..width*.53f -> "larger"
        y in height*.90f..height*.985f && selected!=null && x in width*.53f..width*.69f -> "binding"
        else -> null
    }
    private fun editTouch(event: MotionEvent): Boolean {
        val action=event.actionMasked
        val index=event.actionIndex
        val id=event.getPointerId(index)
        val x=event.getX(index); val y=event.getY(index)
        when(action) {
            MotionEvent.ACTION_DOWN -> {
                editPointer=id; editAction=editorAction(x,y)
                if(editAction==null) {
                    val control=model.hit(x,y)?.takeIf { it.kind!=TouchKind.CAMERA }
                    selected=control?.id
                    if(control!=null) { dragX=x-control.x; dragY=y-control.y }
                }
            }
            MotionEvent.ACTION_MOVE -> if(editPointer!=null && editAction==null) {
                val i=event.findPointerIndex(editPointer!!)
                val control=model.controls.firstOrNull { it.id==selected }
                if(i>=0 && control!=null) placement(control) {
                    it.copy(x=((event.getX(i)-dragX)/width).coerceIn(.025f,.975f),
                        y=((event.getY(i)-dragY)/height).coerceIn(.14f,.88f))
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> if(id==editPointer) {
                val command=editAction?.takeIf { it==editorAction(x,y) }
                editPointer=null; editAction=null
                when(command) {
                    "cancel" -> cancelEditing()
                    "reset" -> { configuration=HudSettings(preset=configuration.preset); rebuild(); selected=null }
                    "save" -> if(onHudSaved(configuration)) { releaseInputs(); isEditing=false }
                    "smaller","larger" -> model.controls.firstOrNull { it.id==selected }?.let { control ->
                        placement(control) { it.copy(scale=(it.scale+if(command=="larger") .10f else -.10f).coerceIn(.55f,1.7f)) }
                    }
                    "binding" -> model.controls.firstOrNull { it.id==selected && it.binding!=null }?.let { onBindingRequested(it.id,it.binding) }
                }
            }
            MotionEvent.ACTION_CANCEL -> { editPointer=null; editAction=null }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); onMenu(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state=model.state(SystemClock.uptimeMillis())
        if(mode==TouchMode.VIRTUAL) {
            model.controls.filter { it.kind!=TouchKind.CAMERA }.forEach { drawControl(canvas,it,state) }
            if(isEditing) drawEditor(canvas) else {
                paint.style=Paint.Style.FILL; paint.color=0xbbe7f2e9.toInt(); paint.textSize=height*.020f; paint.textAlign=Paint.Align.CENTER
                canvas.drawText(preset.label,width*.5f,height*.11f,paint)
            }
        }
        if(!isEditing) {
            paint.style=Paint.Style.FILL; paint.color=0x99101a13.toInt(); canvas.drawCircle(width*.5f,height*.055f,height*.031f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=maxOf(1f,height*.002f); paint.color=0xaa54de69.toInt()
            canvas.drawCircle(width*.5f,height*.055f,height*.031f,paint)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.040f
            canvas.drawText("⋯",width*.5f,height*.055f-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    private fun drawControl(canvas: Canvas,control: TouchControl,state: GamepadState) {
        val active=model.held(control)
        val selected=isEditing && this.selected==control.id
        val opacity=configuration.opacity.coerceIn(20,100)/100f
        fun shape() {
            when(control.shape) {
                HudShape.CIRCLE -> canvas.drawCircle(control.x,control.y,control.radius,paint)
                HudShape.PEDAL -> canvas.drawRoundRect(control.x-control.radius*.72f,control.y-control.radius,
                    control.x+control.radius*.72f,control.y+control.radius,control.radius*.25f,control.radius*.25f,paint)
                HudShape.SQUARE -> canvas.drawRoundRect(control.x-control.radius,control.y-control.radius,
                    control.x+control.radius,control.y+control.radius,control.radius*.18f,control.radius*.18f,paint)
            }
        }
        paint.style=Paint.Style.FILL; paint.color=if(active || selected) Color.argb((160*opacity).toInt(),46,135,69) else Color.argb((105*opacity).toInt(),15,24,20)
        shape()
        paint.style=Paint.Style.STROKE; paint.strokeWidth=if(selected) height*.005f else maxOf(1f,height*.0023f)
        paint.color=if(active || selected) DashboardTheme.accent else Color.argb((215*opacity).toInt(),232,241,236)
        shape()
        paint.color=Color.argb((255*opacity).toInt(),255,255,255)
        if(control.kind==TouchKind.LEFT_STICK || control.kind==TouchKind.RIGHT_STICK) {
            val x=if(control.kind==TouchKind.LEFT_STICK) state.leftX else state.rightX
            val y=if(control.kind==TouchKind.LEFT_STICK) state.leftY else state.rightY
            paint.style=Paint.Style.FILL
            canvas.drawCircle(control.x+x*control.radius*.65f,control.y+y*control.radius*.65f,control.radius*.33f,paint)
        } else if(control.kind==TouchKind.DPAD) {
            paint.style=Paint.Style.STROKE; paint.strokeWidth=control.radius*.18f
            canvas.drawLine(control.x-control.radius*.5f,control.y,control.x+control.radius*.5f,control.y,paint)
            canvas.drawLine(control.x,control.y-control.radius*.5f,control.x,control.y+control.radius*.5f,paint)
        } else if(control.icon!=HudIcon.TEXT) icons.draw(canvas,paint,control.icon,control.x,control.y,control.radius*.55f)
        else {
            paint.style=Paint.Style.FILL; paint.textSize=control.radius*.75f
            canvas.drawText(control.label,control.x,control.y-(paint.ascent()+paint.descent())/2,paint)
        }
        if(control.icon!=HudIcon.TEXT || control.kind==TouchKind.LEFT_STICK || control.kind==TouchKind.RIGHT_STICK || (control.kind==TouchKind.DPAD && control.label!="+")) {
            paint.style=Paint.Style.FILL; paint.textSize=minOf(height*.024f,control.radius*.43f); paint.color=Color.argb((255*opacity).toInt(),255,255,255)
            val textWidth=paint.measureText(control.label)
            if(textWidth>control.radius*2.9f) paint.textSize*=control.radius*2.9f/textWidth
            canvas.drawText(control.label,control.x,control.y+control.radius+height*.023f,paint)
        }
        if(control.icon!=HudIcon.TEXT && control.binding!=null && control.binding !in setOf(HudBinding.STEER_LEFT,HudBinding.STEER_RIGHT)) {
            val key=when(control.binding) {
                HudBinding.MENU -> "☰"; HudBinding.VIEW -> "V"; HudBinding.UP -> "↑"; HudBinding.DOWN -> "↓"
                HudBinding.LEFT -> "←"; HudBinding.RIGHT -> "→"; else -> control.binding.label
            }
            paint.style=Paint.Style.FILL; paint.color=0xc00a120e.toInt()
            canvas.drawCircle(control.x+control.radius*.72f,control.y+control.radius*.62f,control.radius*.30f,paint)
            paint.color=Color.WHITE; paint.textSize=control.radius*.34f
            canvas.drawText(key,control.x+control.radius*.72f,control.y+control.radius*.62f-(paint.ascent()+paint.descent())/2,paint)
        }
    }
    private fun drawEditor(canvas: Canvas) {
        fun button(label: String,left: Float,right: Float,top: Float,bottom: Float,accent: Boolean=false) {
            paint.style=Paint.Style.FILL; paint.color=if(accent) 0xee268943.toInt() else 0xee17261d.toInt()
            canvas.drawRoundRect(width*left,height*top,width*right,height*bottom,height*.012f,height*.012f,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=height*.002f; paint.color=DashboardTheme.accent
            canvas.drawRoundRect(width*left,height*top,width*right,height*bottom,height*.012f,height*.012f,paint)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.027f
            canvas.drawText(label,width*(left+right)/2,height*(top+bottom)/2-(paint.ascent()+paint.descent())/2,paint)
        }
        paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.021f
        canvas.drawText("EDITAR HUD • ARRASTE OS BOTÕES",width*.505f,height*.025f,paint)
        button("Cancelar",.31f,.44f,.035f,.105f)
        button("Restaurar",.44f,.57f,.035f,.105f)
        button("Salvar",.57f,.70f,.035f,.105f,true)
        selected?.let { id -> model.controls.firstOrNull { it.id==id }?.let { control ->
            button("−",.33f,.43f,.90f,.985f); button("+",.43f,.53f,.90f,.985f)
            if(control.binding!=null) button("Ação: ${control.binding.label}",.53f,.69f,.90f,.985f)
        } }
    }
}
