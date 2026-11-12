package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import android.graphics.drawable.Drawable
import com.xcloud.tclnative.settings.ConsoleScene

internal enum class ConsoleControl { TAB,CARD,ACTION,QUICK }

/** Console-specific controls use their own geometry and contrast, independently from the green skin. */
internal class ConsoleControlPlate(private val scene: ConsoleScene,private val control: ConsoleControl,
                                   private val selected: Boolean=false): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private var focused=false
    private var fill: Shader?=null
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean {
        val next=android.R.attr.state_focused in state || android.R.attr.state_pressed in state
        if(next==focused) return false
        focused=next;invalidateSelf();return true
    }
    override fun onBoundsChange(bounds: Rect) {
        fill=LinearGradient(0f,0f,0f,bounds.height().toFloat().coerceAtLeast(1f),
            when(scene) {
                ConsoleScene.XBOX_360 -> intArrayOf(0xfff7f8eb.toInt(),0xffc6d3ab.toInt())
                ConsoleScene.XBOX_ORIGINAL -> intArrayOf(0xffbde547.toInt(),0xff638b0e.toInt())
                else -> intArrayOf(0xff168f28.toInt(),0xff107c10.toInt())
            },null,Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        rect.set(bounds);val h=rect.height();val inset=(h*.045f).coerceAtLeast(1f)
        rect.inset(inset,inset);paint.style=Paint.Style.FILL;paint.shader=null
        when(scene) {
            ConsoleScene.XBOX_360 -> {
                if(control==ConsoleControl.CARD) {
                    paint.color=if(focused) 0xff487811.toInt() else 0x15ffffff
                    canvas.drawRoundRect(rect,5f,5f,paint)
                    paint.color=0x546e843d;canvas.drawLine(rect.left,rect.bottom,rect.right,rect.bottom,paint)
                } else if(control!=ConsoleControl.TAB || focused) {
                    paint.shader=fill;canvas.drawRoundRect(rect,h*.32f,h*.32f,paint);paint.shader=null
                    paint.style=Paint.Style.STROKE;paint.color=0xff7e9258.toInt();paint.strokeWidth=1.5f
                    canvas.drawRoundRect(rect,h*.32f,h*.32f,paint)
                }
            }
            ConsoleScene.XBOX_ONE -> {
                paint.color=when { control==ConsoleControl.ACTION -> 0xff107c10.toInt();control==ConsoleControl.TAB -> Color.TRANSPARENT;else -> 0xff191919.toInt() }
                canvas.drawRect(rect,paint)
                if(control==ConsoleControl.TAB && (selected || focused)) {
                    paint.color=0xff78d342.toInt();canvas.drawRect(rect.left+12f,rect.bottom-3f,rect.right-12f,rect.bottom,paint)
                } else if(focused) { paint.style=Paint.Style.STROKE;paint.strokeWidth=4f;paint.color=Color.WHITE;canvas.drawRect(rect,paint) }
            }
            ConsoleScene.XBOX_SERIES -> {
                val radius=if(control==ConsoleControl.TAB) h/2f else 6f
                paint.color=if(control==ConsoleControl.ACTION) 0xff107c10.toInt() else if(control==ConsoleControl.TAB) 0x90000000.toInt() else 0xe0202426.toInt()
                canvas.drawRoundRect(rect,radius,radius,paint)
                if(focused || control==ConsoleControl.TAB && selected) {
                    paint.style=Paint.Style.STROKE;paint.color=0xff7ee036.toInt();paint.strokeWidth=3f;canvas.drawRoundRect(rect,radius,radius,paint)
                }
            }
            ConsoleScene.XBOX_ORIGINAL -> {
                if(focused || control==ConsoleControl.ACTION || control==ConsoleControl.TAB && selected) {
                    paint.shader=fill;canvas.drawRoundRect(rect,h*.30f,h*.30f,paint);paint.shader=null
                } else {
                    paint.color=0xb51a3305.toInt();canvas.drawRoundRect(rect,h*.30f,h*.30f,paint)
                }
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.5f;paint.color=if(focused) 0xffdfff8a.toInt() else 0xff628a27.toInt()
                canvas.drawRoundRect(rect,h*.30f,h*.30f,paint)
                if(control==ConsoleControl.TAB) {
                    paint.style=Paint.Style.FILL;paint.color=if(focused || selected) 0xffd1ff53.toInt() else 0xff6b9624.toInt()
                    canvas.drawCircle(rect.left+rect.height()*.5f,rect.centerY(),rect.height()*.19f,paint)
                }
            }
            ConsoleScene.NONE -> Unit
        }
        paint.shader=null;paint.style=Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Static structural layers. Wallpaper animation is isolated in ConsoleThemeBackdrop. */
internal class ConsoleDashboardChrome(private val selected: () -> Int): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val panel=Path()
    private val blade=Path()
    private var cachedScene=ConsoleScene.NONE
    private var cachedIndex=-1
    private var panelFill: Shader?=null
    private var shade: Shader?=null
    private var metal: Shader?=null
    private val bladeColors=intArrayOf(0xffd89129.toInt(),0xff8db42a.toInt(),0xff5f941f.toInt(),0xff3b85ac.toInt(),0xff82569f.toInt(),0xff85a541.toInt())
    override fun onBoundsChange(bounds: Rect) { cachedIndex=-1 }
    private fun configure(w: Float,h: Float,scene: ConsoleScene,index: Int) {
        cachedScene=scene;cachedIndex=index
        shade=LinearGradient(0f,0f,0f,h,intArrayOf(0xc9000000.toInt(),0x10000000,0x35000000,0xdd000000.toInt()),floatArrayOf(0f,.25f,.55f,1f),Shader.TileMode.CLAMP)
        metal=LinearGradient(0f,0f,40f,0f,intArrayOf(0xff62685e.toInt(),0xfff2f3ed.toInt(),0xffa1a59b.toInt(),0xff4d534b.toInt()),null,Shader.TileMode.MIRROR)
        panel.reset()
        if(scene==ConsoleScene.XBOX_360) {
            val left=index*44f+26f;val right=w-(5-index)*44f-26f
            panel.moveTo(left+23f,18f);panel.cubicTo(left-28f,170f,left+35f,430f,left,658f)
            panel.lineTo(right,658f);panel.cubicTo(right-35f,430f,right+28f,170f,right-23f,18f);panel.close()
            panelFill=LinearGradient(0f,18f,0f,658f,intArrayOf(0xdda6be5b.toInt(),0xdde8efc7.toInt(),0xddc0d29a.toInt(),0xdd8eaf4b.toInt()),floatArrayOf(0f,.18f,.78f,1f),Shader.TileMode.CLAMP)
        }
    }
    override fun draw(canvas: Canvas) {
        val scene=DashboardTheme.palette.theme.scene
        if(scene==ConsoleScene.NONE || bounds.width()==0 || bounds.height()==0) return
        val s=minOf(bounds.width()/1280f,bounds.height()/720f);val w=bounds.width()/s
        val index=selected()
        if(scene!=cachedScene || index!=cachedIndex) configure(w,bounds.height()/s,scene,index)
        val save=canvas.save();canvas.scale(s,s);paint.shader=null;paint.style=Paint.Style.FILL;paint.alpha=255
        when(scene) {
            ConsoleScene.XBOX_360 -> {
                paint.shader=metal
                repeat(6) { i -> if(i!=index) {
                    val x=if(i<index) i*44f else w-(6-i)*44f
                    blade.reset();blade.moveTo(x,0f);blade.cubicTo(x+18,180f,x-15,430f,x+8,665f)
                    blade.lineTo(x+44,665f);blade.cubicTo(x+22,430f,x+58,180f,x+44,0f);blade.close()
                    canvas.drawPath(blade,paint)
                    paint.shader=null;paint.color=bladeColors[i];paint.alpha=190
                    canvas.drawRect(x+28f,23f,x+34f,638f,paint)
                    paint.alpha=255;paint.shader=metal
                } }
                paint.shader=panelFill;canvas.drawPath(panel,paint);paint.shader=null
                paint.style=Paint.Style.STROKE;paint.strokeWidth=4f;paint.color=0xfff2f3df.toInt();canvas.drawPath(panel,paint)
                paint.style=Paint.Style.FILL;paint.color=0x50243c03
                canvas.drawRect(index*44f+62f,117f,w-(5-index)*44f-62f,119f,paint)
            }
            ConsoleScene.XBOX_ONE -> {
                paint.color=0x99080808.toInt();canvas.drawRect(0f,0f,w,720f,paint)
                paint.color=0xff181818.toInt();canvas.drawRect(0f,95f,w,169f,paint)
                paint.color=0xff107c10.toInt();canvas.drawRect(54f,235f,59f,616f,paint)
            }
            ConsoleScene.XBOX_SERIES -> {
                paint.shader=shade;canvas.drawRect(0f,0f,w,720f,paint);paint.shader=null
                paint.color=0x70000000;canvas.drawRect(0f,670f,w,720f,paint)
            }
            ConsoleScene.XBOX_ORIGINAL -> {
                paint.color=0x50000000;canvas.drawRect(w*.49f,98f,w-45f,630f,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1f;paint.color=0x553c720b
                canvas.drawLine(w*.49f,98f,w*.49f,630f,paint)
            }
            else -> Unit
        }
        paint.shader=null;canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
