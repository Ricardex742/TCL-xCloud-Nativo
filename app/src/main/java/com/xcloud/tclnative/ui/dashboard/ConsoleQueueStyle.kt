package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import android.graphics.drawable.Drawable
import com.xcloud.tclnative.settings.ConsoleScene

/** A static launch surface matching the console dashboard; no additional animation loop. */
internal class ConsoleQueueChrome(private val scene: ConsoleScene): Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private var shade: Shader?=null
    private var panel: Shader?=null
    override fun onBoundsChange(b: Rect) {
        shade=LinearGradient(0f,0f,b.width().toFloat(),b.height().toFloat(),intArrayOf(0xf5090d11.toInt(),0xc5080c10.toInt(),0x8704080d.toInt()),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
        panel=LinearGradient(0f,0f,0f,b.height().toFloat(),intArrayOf(0xffedf2d8.toInt(),0xffb0c576.toInt()),null,Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) {
        p.style=Paint.Style.FILL;p.shader=null;p.alpha=255
        when(scene) {
            ConsoleScene.XBOX_360 -> {
                val s=minOf(bounds.width()/1280f,bounds.height()/720f);val ox=(bounds.width()-1280*s)/2f
                val save=c.save();c.translate(ox,0f);c.scale(s,s)
                p.color=0xffb1b8a9.toInt();c.drawRect(-ox/s,0f,(bounds.width()-ox)/s,720f,p)
                path.reset();path.moveTo(64f,24f);path.cubicTo(26f,210f,75f,440f,42f,654f)
                path.lineTo(1238f,654f);path.cubicTo(1205f,440f,1254f,210f,1216f,24f);path.close()
                p.shader=panel;c.drawPath(path,p);p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=4f;p.color=Color.WHITE;c.drawPath(path,p)
                p.strokeWidth=1f;p.color=0xff7e925c.toInt();c.drawLine(94f,174f,1186f,174f,p)
                c.restoreToCount(save)
            }
            ConsoleScene.XBOX_ONE -> { p.color=0xe5080808.toInt();c.drawRect(bounds,p) }
            ConsoleScene.XBOX_SERIES -> { p.shader=shade;c.drawRect(bounds,p);p.shader=null }
            ConsoleScene.XBOX_ORIGINAL -> { p.color=0x55000000;c.drawRect(bounds,p) }
            else -> Unit
        }
        p.style=Paint.Style.FILL;p.shader=null
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

internal class ConsoleQueueStep(private val scene: ConsoleScene,private val index: Int,
                                private val active: Boolean,private val complete: Boolean): Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val r=RectF()
    private val check=Path()
    override fun draw(c: Canvas) {
        r.set(bounds);r.inset(2f,2f);p.style=Paint.Style.FILL;p.alpha=255;p.shader=null
        if(scene==ConsoleScene.XBOX_SERIES) {
            val radius=r.height()*.28f;val x=r.left+radius+4f;val y=r.centerY()
            p.color=if(active || complete) 0xff7bdb3b.toInt() else 0xff5b656b.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=2f
            c.drawCircle(x,y,radius,p)
            if(complete) { check.reset();check.moveTo(x-radius*.5f,y);check.lineTo(x-radius*.1f,y+radius*.4f);check.lineTo(x+radius*.5f,y-radius*.4f);c.drawPath(check,p) }
            else { p.style=Paint.Style.FILL;p.textAlign=Paint.Align.CENTER;p.typeface=Typeface.DEFAULT_BOLD;p.textSize=radius*1.1f;c.drawText("${index+1}",x,y+radius*.38f,p) }
        } else {
            p.color=when(scene) {
                ConsoleScene.XBOX_360 -> if(active) 0xff6b8d24.toInt() else 0xffe4ead0.toInt()
                ConsoleScene.XBOX_360_NXE -> if(active) 0xff388514.toInt() else 0xffdae0d6.toInt()
                ConsoleScene.XBOX_360_METRO -> if(active) 0xff168d07.toInt() else 0xffc4cdc0.toInt()
                ConsoleScene.XBOX_ONE -> if(active) 0xff107c10.toInt() else 0xff242424.toInt()
                else -> if(active) 0xff9dce32.toInt() else 0xff142a08.toInt()
            }
            val radius=if(scene==ConsoleScene.XBOX_ONE || scene==ConsoleScene.XBOX_360_METRO) 0f else if(scene==ConsoleScene.XBOX_360_NXE) 4f else r.height()*.3f;c.drawRoundRect(r,radius,radius,p)
            if(active) { p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(scene==ConsoleScene.XBOX_360) 0xff446016.toInt() else 0xffa9df59.toInt();c.drawRoundRect(r,radius,radius,p) }
        }
        p.style=Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
