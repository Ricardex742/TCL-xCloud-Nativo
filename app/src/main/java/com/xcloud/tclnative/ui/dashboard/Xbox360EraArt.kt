package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import com.xcloud.tclnative.settings.ConsoleScene
import kotlin.math.sin

/** Original vector scenery: the NXE horizon and the restrained Metro ribbon, without image assets. */
internal class Xbox360EraArt {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val ribbons=Array(3) { Path() }
    private val rect=RectF()
    private val mark=Path().apply {
        moveTo(-16f,-17f);quadTo(-4f,-15f,0f,-7f);quadTo(4f,-15f,16f,-17f)
        lineTo(5f,0f);lineTo(18f,19f);quadTo(5f,17f,0f,8f);quadTo(-5f,17f,-18f,19f);lineTo(-5f,0f);close()
    }
    private var scene=ConsoleScene.NONE
    private var h=562.5f
    private var sky: Shader?=null
    private var floor: Shader?=null
    private var orb: Shader?=null
    fun configure(value: ConsoleScene,height: Float) {
        scene=value;h=height
        sky=if(scene==ConsoleScene.XBOX_360_NXE)
            LinearGradient(0f,0f,0f,h*.8f,intArrayOf(0xff273c2d.toInt(),0xff52794b.toInt(),0xffaab6a4.toInt()),null,Shader.TileMode.CLAMP)
        else LinearGradient(0f,0f,0f,h,intArrayOf(0xffd8dbd5.toInt(),0xffe6e8e3.toInt(),0xffbfc5bc.toInt()),null,Shader.TileMode.CLAMP)
        floor=LinearGradient(0f,h*.61f,0f,h,intArrayOf(0xfff1f3ee.toInt(),0xffb8c0b5.toInt(),0xff848e82.toInt()),null,Shader.TileMode.CLAMP)
        orb=RadialGradient(-8f,-10f,28f,intArrayOf(Color.WHITE,0xffd6dcd2.toInt(),0xff7d8778.toInt()),null,Shader.TileMode.CLAMP)
        ribbons.forEachIndexed { i,path ->
            path.reset();path.moveTo(-150f,h*.28f+i*35f)
            path.cubicTo(120f,h*.06f,380f,h*.88f,1150f,h*.02f+i*65f)
            path.lineTo(1150f,h*.20f+i*65f)
            path.cubicTo(400f,h*1.04f,120f,h*.23f,-150f,h*.44f+i*35f);path.close()
        }
    }
    fun draw(c: Canvas,phase: Float) {
        p.style=Paint.Style.FILL;p.alpha=255;p.shader=sky;c.drawRect(0f,0f,1000f,h,p);p.shader=null
        val wave=sin(phase*2*Math.PI).toFloat()
        val save=c.save();c.translate(wave*42f,wave*12f)
        ribbons.forEach { path ->
            p.color=if(scene==ConsoleScene.XBOX_360_NXE) 0x287ca96a else 0x236e8465
            c.drawPath(path,p);p.style=Paint.Style.STROKE;p.strokeWidth=.8f
            p.color=if(scene==ConsoleScene.XBOX_360_NXE) 0x3588b071 else 0x208f9e87
            c.drawPath(path,p);p.style=Paint.Style.FILL
        };c.restoreToCount(save)
        if(scene==ConsoleScene.XBOX_360_NXE) {
            p.shader=floor;c.drawRect(0f,h*.65f,1000f,h,p);p.shader=null
            p.color=0x55ffffff;c.drawRect(0f,h*.65f,1000f,h*.653f,p)
            val ball=c.save();c.translate(941f,h-53f)
            rect.set(-33f,23f,33f,29f);p.color=0x22505c4c;c.drawOval(rect,p)
            p.shader=orb;c.drawCircle(0f,0f,25f,p);p.shader=null;p.color=0xff468228.toInt();c.drawPath(mark,p)
            c.restoreToCount(ball)
        }
        p.shader=null;p.alpha=255
    }
}
