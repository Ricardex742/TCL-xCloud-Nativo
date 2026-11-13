package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import android.graphics.drawable.Drawable
import com.xcloud.tclnative.settings.WallpaperStyle
import kotlin.math.sin

/** Original vector backgrounds. Paths/shaders are cached; motion uses canvas transforms, no blur or video. */
internal class SeriesWallpaperArt(private val style: WallpaperStyle): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val paths=List(4) { Path() }
    private var wash: Shader?=null
    private var ribbon: Shader?=null
    private var w=0f;private var h=0f
    var phase=0f
    private val colors=when(style) {
        WallpaperStyle.FLOW -> intArrayOf(0xff03110d.toInt(),0xff15783b.toInt(),0xff82ed92.toInt())
        WallpaperStyle.HORIZON -> intArrayOf(0xff061c1d.toInt(),0xff186e70.toInt(),0xff78e9bb.toInt())
        WallpaperStyle.AURORA -> intArrayOf(0xff0a102b.toInt(),0xff4b3190.toInt(),0xff51ded3.toInt())
        WallpaperStyle.ORBIT -> intArrayOf(0xff100d26.toInt(),0xff493375.toInt(),0xffba9df3.toInt())
        WallpaperStyle.SUNSET -> intArrayOf(0xff230f23.toInt(),0xffa5484c.toInt(),0xffffc38a.toInt())
        WallpaperStyle.PLATINUM -> intArrayOf(0xff101b29.toInt(),0xff52677a.toInt(),0xffdceaf2.toInt())
    }
    override fun onBoundsChange(bounds: Rect) {
        w=bounds.width().toFloat();h=bounds.height().toFloat()
        if(w<=0 || h<=0) return
        wash=LinearGradient(0f,0f,w,h,colors[0],colors[1],Shader.TileMode.CLAMP)
        ribbon=LinearGradient(0f,h,w,0f,intArrayOf(colors[1],colors[2],colors[1]),null,Shader.TileMode.CLAMP)
        paths.forEachIndexed { i,p ->
            val offset=i*h*.17f;p.reset();p.moveTo(-w*.15f,h*.75f+offset)
            p.cubicTo(w*.25f,-h*.25f+offset,w*.7f,h*1.25f-offset,w*1.2f,h*.15f+offset)
        }
    }
    override fun draw(canvas: Canvas) {
        if(w<=0 || h<=0) return
        val save=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.clipRect(0f,0f,w,h)
        paint.style=Paint.Style.FILL;paint.alpha=255;paint.shader=wash;canvas.drawRect(0f,0f,w,h,paint)
        val wave=sin(phase*2*Math.PI).toFloat();canvas.translate(wave*w*.018f,wave*h*.012f)
        paint.shader=ribbon
        when(style) {
            WallpaperStyle.ORBIT -> {
                paint.style=Paint.Style.STROKE
                repeat(5) { i -> paint.strokeWidth=w*(.008f+i*.004f);paint.alpha=130-i*20
                    canvas.drawCircle(w*.68f,h*.5f,h*(.24f+i*.17f),paint) }
            }
            WallpaperStyle.HORIZON,WallpaperStyle.SUNSET -> {
                paint.alpha=220;canvas.drawCircle(w*.72f,h*.41f,h*.22f,paint)
                paint.style=Paint.Style.STROKE
                paths.forEachIndexed { i,p -> paint.alpha=150-i*25;paint.strokeWidth=h*(.055f+i*.013f);canvas.drawPath(p,paint) }
            }
            else -> {
                paint.style=Paint.Style.STROKE
                paths.forEachIndexed { i,p -> paint.alpha=180-i*32;paint.strokeWidth=h*(.065f+i*.018f);canvas.drawPath(p,paint) }
            }
        }
        paint.shader=null;paint.style=Paint.Style.FILL;paint.color=Color.BLACK;paint.alpha=90
        canvas.drawRect(-w*.05f,-h*.05f,w*1.05f,h*1.05f,paint);paint.alpha=255
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
}
