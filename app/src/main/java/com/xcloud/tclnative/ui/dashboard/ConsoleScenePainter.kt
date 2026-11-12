package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import com.xcloud.tclnative.settings.ConsoleScene
import kotlin.math.sin

/** Procedural console-inspired artwork. Geometry/shaders are cached; drawing allocates no assets. */
internal class ConsoleScenePainter {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val paths=Array(4) { Path() }
    private val tile=RectF()
    private var scene=ConsoleScene.NONE
    private var scale=1f
    private var height=562.5f
    private var glow: Shader?=null
    private var silver: Shader?=null

    fun configure(palette: UiPalette,width: Int,height: Int) {
        scene=palette.theme.scene
        if(width<=0 || height<=0 || scene==ConsoleScene.NONE) return
        scale=width/1000f;this.height=height/scale
        val h=this.height
        glow=RadialGradient(0f,0f,330f,intArrayOf(palette.theme.accent and 0x00ffffff or 0x55000000,
            palette.theme.accent and 0x00ffffff or 0x16000000,Color.TRANSPARENT),floatArrayOf(0f,.4f,1f),Shader.TileMode.CLAMP)
        silver=LinearGradient(0f,0f,500f,h,intArrayOf(0x0addede8,0x426e8d70,0x06ffffff),null,Shader.TileMode.CLAMP)
        paths.forEach { it.reset() }
        when(scene) {
            ConsoleScene.XBOX_360 -> repeat(4) { i ->
                val x=120f+i*140f
                paths[i].apply {
                    moveTo(x,-80f);cubicTo(x+420f,h*.2f,x-300f,h*.76f,x+180f,h+80f)
                    lineTo(x+350f,h+80f);cubicTo(x-170f,h*.7f,x+560f,h*.25f,x+180f,-80f);close()
                }
            }
            ConsoleScene.XBOX_SERIES -> repeat(4) { i ->
                val x=160f+i*150f
                paths[i].apply {
                    moveTo(x-270f,h+100f);cubicTo(x-350f,h*.15f,x+210f,h*.4f,x+470f,-90f)
                    lineTo(x+640f,-90f);cubicTo(x+240f,h*.72f,x-190f,h*.2f,x-130f,h+100f);close()
                }
            }
            else -> Unit
        }
    }

    /** phase is a wrapping 0..1 cycle, independent of stream frames. */
    fun draw(canvas: Canvas,phase: Float) {
        if(scene==ConsoleScene.NONE) return
        val wave=sin(phase*2f*Math.PI).toFloat()
        val other=sin(phase*2f*Math.PI+1.5f).toFloat()
        val saved=canvas.save();canvas.scale(scale,scale)
        paint.style=Paint.Style.FILL;paint.shader=null;paint.alpha=255
        when(scene) {
            ConsoleScene.XBOX_360 -> {
                canvas.translate(wave*23f,other*9f)
                paint.shader=silver
                repeat(4) { canvas.drawPath(paths[it],paint) }
                paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=1.1f
                paint.color=0x4bdbefce
                repeat(4) { canvas.drawPath(paths[it],paint) }
            }
            ConsoleScene.XBOX_ONE -> {
                canvas.translate(wave*12f,other*7f)
                repeat(4) { row -> repeat(5) { col ->
                    val x=38f+col*207f;val y=15f+row*(height/3f)
                    tile.set(x,y,x+188f,y+height/3f-18f)
                    paint.color=if((row+col)%3==0) 0x233d9f48 else 0x1232733d
                    canvas.drawRoundRect(tile,3f,3f,paint)
                } }
                drawGlow(canvas,700f+wave*100f,height*.4f)
            }
            ConsoleScene.XBOX_SERIES -> {
                canvas.translate(wave*27f,other*13f)
                repeat(4) { i ->
                    paint.color=if(i%2==0) 0x26366c66 else 0x26347b59
                    canvas.drawPath(paths[i],paint)
                    paint.style=Paint.Style.STROKE;paint.strokeWidth=1.3f;paint.color=0x344eaa91
                    canvas.drawPath(paths[i],paint);paint.style=Paint.Style.FILL
                }
            }
            ConsoleScene.XBOX_ORIGINAL -> {
                val x=740f+wave*40f;val y=height*.57f+other*20f
                drawGlow(canvas,x,y)
                canvas.rotate(wave*12f,x,y)
                paint.style=Paint.Style.STROKE
                repeat(5) { i ->
                    paint.strokeWidth=if(i==0) 5f else 1.6f
                    paint.color=if(i==0) 0x547aca20 else 0x2879ad39
                    val r=85f+i*43f;tile.set(x-r*1.5f,y-r,x+r*1.5f,y+r)
                    canvas.drawOval(tile,paint)
                }
            }
            ConsoleScene.NONE -> Unit
        }
        paint.shader=null;paint.style=Paint.Style.FILL;paint.alpha=255
        canvas.restoreToCount(saved)
    }
    private fun drawGlow(canvas: Canvas,x: Float,y: Float) {
        val saved=canvas.save();canvas.translate(x,y)
        paint.shader=glow;canvas.drawCircle(0f,0f,330f,paint);paint.shader=null
        canvas.restoreToCount(saved)
    }
}
