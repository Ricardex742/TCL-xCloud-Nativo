package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.ConsoleScene

internal class ConsolePanelSkin(val scene: ConsoleScene=DashboardTheme.palette.theme.scene) {
    val enabled=scene!=ConsoleScene.NONE
    val light=scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO
    val ink=if(light) 0xff263024.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffccf68a.toInt() else Color.WHITE
    val muted=if(light) 0xff4e5c42.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffa9c57d.toInt() else 0xffc4cbd0.toInt()
    val accent=if(light) 0xff3c7015.toInt() else DashboardTheme.accent
    val columns=if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_360_METRO || scene==ConsoleScene.XBOX_SERIES) 2 else 1
    fun font(heavy: Boolean=false): Typeface=Typeface.create(if(scene==ConsoleScene.XBOX_ORIGINAL) "sans-serif-condensed" else "sans-serif",if(heavy) Typeface.BOLD else Typeface.NORMAL)
    fun textColor(requested: Int)=when(requested) {
        Color.WHITE -> ink
        DashboardTheme.muted -> muted
        DashboardTheme.accent -> accent
        else -> requested
    }
    fun background(): Drawable=ConsolePanelChrome(scene)
    fun row(tile: Boolean=false,index: Int=0): Drawable=ConsolePanelRow(scene,tile,index)
    fun textColors(tile: Boolean=false): ColorStateList {
        val normal=if(tile && scene==ConsoleScene.XBOX_360_METRO) Color.WHITE else ink
        val active=if(scene==ConsoleScene.XBOX_ORIGINAL) 0xff193400.toInt() else if(light) Color.WHITE else ink
        return ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),intArrayOf(active,active,normal))
    }
    fun input(scale: Float): Drawable=GradientDrawable().apply {
        setColor(if(light) 0xfff3f5e9.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xff0b1f03.toInt() else 0xff10171a.toInt())
        cornerRadius=if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_360_METRO) 0f else 6*scale
        setStroke((2*scale).toInt().coerceAtLeast(1),accent)
    }
}

/** Cached shaders and paths give dialogs the console's panel geometry, not a recolored generic popup. */
private class ConsolePanelChrome(private val scene: ConsoleScene): Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val r=RectF()
    private val path=Path()
    private var fill: Shader?=null
    override fun onBoundsChange(b: Rect) {
        val colors=when(scene) {
            ConsoleScene.XBOX_360 -> intArrayOf(0xffedf2d7.toInt(),0xffd3deb1.toInt(),0xffb2c57c.toInt())
            ConsoleScene.XBOX_360_NXE -> intArrayOf(0xff36553a.toInt(),0xff243b27.toInt(),0xff6c7c61.toInt())
            ConsoleScene.XBOX_360_METRO -> intArrayOf(0xffe2e6dd.toInt(),0xffd3d9ce.toInt(),0xffc4cdbd.toInt())
            ConsoleScene.XBOX_ONE -> intArrayOf(0xff101010.toInt(),0xff161616.toInt(),0xff101010.toInt())
            ConsoleScene.XBOX_SERIES -> intArrayOf(0xfa18252c.toInt(),0xfa10191f.toInt(),0xfa0a1116.toInt())
            else -> intArrayOf(0xff132707.toInt(),0xff071701.toInt(),0xff102600.toInt())
        }
        fill=LinearGradient(0f,0f,b.width().toFloat(),b.height().toFloat(),colors,null,Shader.TileMode.CLAMP)
        path.reset()
        if(scene==ConsoleScene.XBOX_360) {
            val w=b.width().toFloat();val h=b.height().toFloat()
            path.moveTo(25f,2f);path.lineTo(w-5f,2f);path.lineTo(w-5f,h-2f);path.lineTo(12f,h-2f)
            path.cubicTo(31f,h*.6f,-12f,h*.3f,25f,2f);path.close()
        }
    }
    override fun draw(c: Canvas) {
        r.set(bounds);r.inset(2f,2f);p.style=Paint.Style.FILL;p.shader=fill
        val radius=when(scene) { ConsoleScene.XBOX_360 -> 0f;ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 0f;ConsoleScene.XBOX_ORIGINAL -> 28f;else -> 14f }
        if(scene==ConsoleScene.XBOX_360) c.drawPath(path,p) else c.drawRoundRect(r,radius,radius,p)
        p.shader=null
        when(scene) {
            ConsoleScene.XBOX_360 -> { p.style=Paint.Style.STROKE;p.color=0xfff8f9ee.toInt();p.strokeWidth=4f;c.drawPath(path,p) }
            ConsoleScene.XBOX_360_NXE -> { p.color=0x275fe241;c.drawRect(r.left,r.top,r.right,r.top+r.height()*.19f,p);p.color=0x337d9c68;c.drawRect(r.left,r.bottom-26f,r.right,r.bottom,p) }
            ConsoleScene.XBOX_360_METRO -> { p.color=0xff168d07.toInt();c.drawRect(r.left,r.top,r.left+12f,r.bottom,p) }
            ConsoleScene.XBOX_ONE -> { p.color=0xff107c10.toInt();c.drawRect(r.left,r.top,r.right,r.top+7f,p) }
            ConsoleScene.XBOX_SERIES -> { p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x99839d9d.toInt();c.drawRoundRect(r,14f,14f,p) }
            ConsoleScene.XBOX_ORIGINAL -> { p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=0xff5d8a1b.toInt();c.drawRoundRect(r,28f,28f,p);p.strokeWidth=1f;r.inset(7f,7f);p.color=0x557ac530;c.drawRoundRect(r,24f,24f,p) }
            else -> Unit
        }
        p.shader=null;p.style=Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

private class ConsolePanelRow(private val scene: ConsoleScene,private val tile: Boolean,private val index: Int): Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val r=RectF()
    private val icons=ConsoleIcons()
    private var focused=false
    private var gradient: Shader?=null
    private val glyphs=listOf(ConsoleGlyph.DISPLAY,ConsoleGlyph.IMAGE,ConsoleGlyph.PALETTE,ConsoleGlyph.NETWORK,ConsoleGlyph.CONTROLLER,ConsoleGlyph.AUDIO,ConsoleGlyph.DIAGNOSTICS,ConsoleGlyph.ACCOUNT)
    override fun isStateful()=true
    override fun onStateChange(s: IntArray): Boolean {
        focused=android.R.attr.state_focused in s || android.R.attr.state_pressed in s;invalidateSelf();return true
    }
    override fun onBoundsChange(b: Rect) {
        gradient=LinearGradient(0f,0f,0f,b.height().toFloat().coerceAtLeast(1f),intArrayOf(0xffbbde68.toInt(),0xff669d21.toInt()),null,Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) {
        r.set(bounds);r.inset(3f,3f);p.style=Paint.Style.FILL;p.shader=null
        val light=scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_METRO
        p.color=when(scene) {
            ConsoleScene.XBOX_360 -> if(focused) 0xff527e20.toInt() else 0xffedf1d9.toInt()
            ConsoleScene.XBOX_360_NXE -> if(focused) 0xff288616.toInt() else 0x6822311b
            ConsoleScene.XBOX_360_METRO -> if(tile || focused) 0xff168d07.toInt() else 0xffe9ede2.toInt()
            ConsoleScene.XBOX_ONE -> if(tile) 0xff107c10.toInt() else 0xff232323.toInt()
            ConsoleScene.XBOX_SERIES -> if(focused) 0xff2b414a.toInt() else 0xff1d2d35.toInt()
            else -> if(focused) 0xff91c92a.toInt() else 0xff183307.toInt()
        }
        val radius=when(scene) { ConsoleScene.XBOX_360,ConsoleScene.XBOX_ORIGINAL -> r.height()*.27f;ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 0f;ConsoleScene.XBOX_360_NXE -> 4f;else -> 8f }
        if(scene==ConsoleScene.XBOX_ORIGINAL && focused) p.shader=gradient
        c.drawRoundRect(r,radius,radius,p);p.shader=null
        if(focused) {
            p.style=Paint.Style.STROKE;p.strokeWidth=3f;p.color=if(light) 0xff426c13.toInt() else Color.WHITE
            if(scene==ConsoleScene.XBOX_360_METRO) p.color=if(tile) Color.WHITE else 0xff387916.toInt()
            c.drawRoundRect(r,radius,radius,p);p.style=Paint.Style.FILL
        }
        if(tile) {
            val size=(r.height()*.33f).coerceAtMost(37f)
            icons.draw(c,glyphs[index%glyphs.size],r.left+17f,r.top+10f,size,Color.WHITE)
        }
        if(scene==ConsoleScene.XBOX_ORIGINAL && !tile) {
            p.color=if(focused) 0xff1f4000.toInt() else 0xffa7da4f.toInt();c.drawCircle(r.left+22f,r.centerY(),4f,p)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
