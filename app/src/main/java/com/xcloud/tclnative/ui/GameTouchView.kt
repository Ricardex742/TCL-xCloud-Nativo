package com.xcloud.tclnative.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.xcloud.tclnative.settings.TouchMode
import com.xcloud.tclnative.ui.dashboard.DashboardTheme
import com.xcloud.tclnative.webrtc.*
import kotlin.math.hypot

/** Native Android overlay; no WebView, bitmap capture, or game-specific scripts. */
class GameTouchView(context: Context,
    private val onGamepad: (GamepadState) -> Unit,
    private val onPointers: (List<NativePointer>) -> Unit,
    private val coordinates: (Float,Float) -> Pair<Float,Float>?,
    private val onMenu: () -> Unit
) : View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface=DashboardTheme.font(context,true); textAlign=Paint.Align.CENTER }
    private var model=VirtualGamepad(1,1)
    private var mode=TouchMode.OFF
    private val native=mutableMapOf<Int,NativePointer>()
    private var menuPointer: Int?=null
    init { visibility=GONE; isFocusable=false; contentDescription="Controles de toque e menu da partida" }
    fun configure(mode: TouchMode) { releaseInputs(); this.mode=mode; visibility=if(mode==TouchMode.OFF) GONE else VISIBLE; invalidate() }
    fun releaseInputs() {
        model.clear(); onGamepad(GamepadState())
        if(native.isNotEmpty()) onPointers(native.values.map { it.copy(type=2,pressure=0f) })
        native.clear(); menuPointer=null; invalidate()
    }
    override fun onSizeChanged(w: Int,h: Int,oldw: Int,oldh: Int) {
        releaseInputs(); model=VirtualGamepad(w,h)
    }
    private fun menuHit(x: Float,y: Float) = hypot(x-width*.50f,y-height*.055f)<=height*.038f
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(mode==TouchMode.OFF) return false
        val action=event.actionMasked
        val index=event.actionIndex
        val id=event.getPointerId(index)
        if((action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_POINTER_DOWN) && menuHit(event.getX(index),event.getY(index))) {
            menuPointer=id; return true
        }
        if(action==MotionEvent.ACTION_CANCEL) { releaseInputs(); return true }
        if((action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_POINTER_UP) && id==menuPointer) {
            menuPointer=null
            if(menuHit(event.getX(index),event.getY(index))) { releaseInputs(); performClick() }
            return true
        }
        if(mode==TouchMode.NATIVE) {
            val events=mutableListOf<NativePointer>()
            fun pointer(i: Int,type: Int) {
                val pointerId=event.getPointerId(i)
                if(pointerId==menuPointer) return
                val position=coordinates(event.getX(i),event.getY(i))
                val old=native[pointerId]
                if(type==1 && native.size>=10) return
                if(type==1 && position==null) return
                if(type!=1 && old==null) return
                val (x,y)=position ?: old?.let { it.x to it.y } ?: return
                val pressure=event.getPressure(i).let { if(it.isFinite()) it.coerceIn(0f,1f) else 1f }
                val p=NativePointer(pointerId,x,y,type,if(type==2) 0f else pressure)
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
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> model.down(id,event.getX(index),event.getY(index))
                MotionEvent.ACTION_UP,MotionEvent.ACTION_POINTER_UP -> model.up(id)
                MotionEvent.ACTION_MOVE -> repeat(event.pointerCount) { i -> if(event.getPointerId(i)!=menuPointer) model.move(event.getPointerId(i),event.getX(i),event.getY(i)) }
            }
            onGamepad(model.state()); invalidate()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); onMenu(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state=model.state()
        fun circle(x: Float,y: Float,radius: Float,label: String,held: Boolean) {
            paint.style=Paint.Style.FILL; paint.color=if(held) 0x8854de69.toInt() else 0x48101a13
            canvas.drawCircle(x,y,radius,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=maxOf(1f,height*.003f); paint.color=0xaa54de69.toInt()
            canvas.drawCircle(x,y,radius,paint)
            paint.style=Paint.Style.FILL; paint.color=Color.WHITE; paint.textSize=height*.034f
            canvas.drawText(label,x,y-(paint.ascent()+paint.descent())/2,paint)
        }
        if(mode==TouchMode.VIRTUAL) model.controls.forEach { c ->
            circle(c.x,c.y,c.radius,c.label,model.held(c))
            if(c.kind==TouchKind.LEFT_STICK || c.kind==TouchKind.RIGHT_STICK) {
                val x=if(c.kind==TouchKind.LEFT_STICK) state.leftX else state.rightX
                val y=if(c.kind==TouchKind.LEFT_STICK) state.leftY else state.rightY
                paint.color=0x9954de69.toInt()
                canvas.drawCircle(c.x+x*c.radius*.65f,c.y+y*c.radius*.65f,c.radius*.31f,paint)
            }
        }
        circle(width*.50f,height*.055f,height*.031f,"⋯",false)
    }
}
