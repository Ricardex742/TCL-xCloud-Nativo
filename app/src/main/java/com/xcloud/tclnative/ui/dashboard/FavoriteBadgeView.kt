package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.*
import android.view.View
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.UiTheme
import kotlin.math.cos
import kotlin.math.sin

/** Resolution-independent favorite marker. Its shape and finish follow the dashboard generation. */
internal class FavoriteBadgeView(context: Context): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val star=Path().apply {
        repeat(10) { i ->
            val angle=Math.PI*i/5-Math.PI/2
            val radius=if(i%2==0) 8.5 else 3.8
            val x=(12+cos(angle)*radius).toFloat();val y=(12+sin(angle)*radius).toFloat()
            if(i==0) moveTo(x,y) else lineTo(x,y)
        };close()
    }
    private var theme: UiTheme?=null
    private var scene=ConsoleScene.NONE
    private var top=0;private var bottom=0;private var ink=0;private var rim=0
    private var surface: Shader?=null
    init { isFocusable=false;isClickable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;refresh() }
    fun refresh() {
        val next=DashboardTheme.palette.theme
        if(theme==next) return
        theme=next;scene=next.scene
        top=when(scene) {
            ConsoleScene.XBOX_360 -> 0xfff2f4df.toInt()
            ConsoleScene.XBOX_360_NXE -> 0xee657751.toInt()
            ConsoleScene.XBOX_360_METRO -> 0xff168d07.toInt()
            ConsoleScene.XBOX_ONE -> 0xee181818.toInt()
            ConsoleScene.XBOX_SERIES -> 0xee202629.toInt()
            ConsoleScene.XBOX_ORIGINAL -> 0xff233e08.toInt()
            else -> DashboardTheme.surface
        }
        bottom=when(scene) {
            ConsoleScene.XBOX_360 -> 0xffbdc99e.toInt()
            ConsoleScene.XBOX_360_NXE -> 0xee28371f.toInt()
            ConsoleScene.XBOX_ORIGINAL -> 0xff061600.toInt()
            else -> top
        }
        ink=when(scene) {
            ConsoleScene.XBOX_360 -> 0xff536e1b.toInt()
            ConsoleScene.XBOX_360_NXE -> 0xfff2ce61.toInt()
            ConsoleScene.XBOX_360_METRO,ConsoleScene.XBOX_ONE -> Color.WHITE
            ConsoleScene.XBOX_SERIES -> 0xff9bf00b.toInt()
            ConsoleScene.XBOX_ORIGINAL -> 0xffb4ed43.toInt()
            else -> DashboardTheme.yellow
        }
        rim=when(scene) { ConsoleScene.XBOX_360 -> 0xff81945b.toInt();ConsoleScene.XBOX_ORIGINAL -> 0xff78a42b.toInt();else -> Color.TRANSPARENT }
        surface=LinearGradient(0f,0f,0f,24f,top,bottom,Shader.TileMode.CLAMP);invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save=canvas.save();val side=minOf(width,height).toFloat()
        canvas.translate((width-side)/2,(height-side)/2);canvas.scale(side/24f,side/24f)
        val radius=when(scene) { ConsoleScene.XBOX_360 -> 8f;ConsoleScene.XBOX_ORIGINAL -> 10f;ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 0f;else -> 4f }
        paint.style=Paint.Style.FILL;paint.alpha=255;paint.shader=surface
        canvas.drawRoundRect(.5f,.5f,23.5f,23.5f,radius,radius,paint)
        paint.shader=null
        if(rim!=Color.TRANSPARENT) { paint.color=rim;paint.style=Paint.Style.STROKE;paint.strokeWidth=.8f;canvas.drawRoundRect(.5f,.5f,23.5f,23.5f,radius,radius,paint) }
        paint.style=Paint.Style.FILL;paint.color=ink;canvas.drawPath(star,paint)
        canvas.restoreToCount(save)
    }
}
