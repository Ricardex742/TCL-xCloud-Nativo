package com.xcloud.tclnative.ui.dashboard

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

internal enum class ConsoleGlyph { HOME,FAVORITE,GAME_PASS,PURCHASED,RECENT,LIBRARY,SEARCH,SETTINGS }

/** Resolution-independent navigation marks, rather than device-dependent Unicode glyphs. */
internal class ConsoleIcons {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val path=Path()
    private val rect=RectF()
    private val gear=Path().apply {
        repeat(32) { i ->
            val angle=i*Math.PI/16;val r=if(i%4 in 1..2) 10f else 8f
            val x=12f+cos(angle).toFloat()*r;val y=12f+sin(angle).toFloat()*r
            if(i==0) moveTo(x,y) else lineTo(x,y)
        };close()
    }
    private val font=Typeface.create("sans-serif-black",Typeface.NORMAL)
    fun draw(c: Canvas,icon: ConsoleGlyph,x: Float,y: Float,size: Float,color: Int=Color.WHITE) {
        val save=c.save();c.translate(x,y);c.scale(size/24f,size/24f)
        p.shader=null;p.color=color;p.style=Paint.Style.STROKE;p.strokeWidth=1.65f;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        path.reset()
        when(icon) {
            ConsoleGlyph.HOME -> { path.moveTo(3f,11f);path.lineTo(12f,3f);path.lineTo(21f,11f);path.moveTo(5f,9f);path.lineTo(5f,21f);path.lineTo(10f,21f);path.lineTo(10f,15f);path.lineTo(14f,15f);path.lineTo(14f,21f);path.lineTo(19f,21f);path.lineTo(19f,9f);c.drawPath(path,p) }
            ConsoleGlyph.FAVORITE -> { path.moveTo(12f,21f);path.cubicTo(9f,17f,2f,13f,2f,7f);path.cubicTo(2f,1f,10f,1f,12f,7f);path.cubicTo(14f,1f,22f,1f,22f,7f);path.cubicTo(22f,13f,15f,17f,12f,21f);c.drawPath(path,p) }
            ConsoleGlyph.GAME_PASS -> { rect.set(2f,3f,22f,21f);c.drawRoundRect(rect,2f,2f,p);p.style=Paint.Style.FILL;p.typeface=font;p.textAlign=Paint.Align.CENTER;p.textSize=6.2f;c.drawText("GAME",12f,11f,p);c.drawText("PASS",12f,18f,p) }
            ConsoleGlyph.PURCHASED -> { rect.set(3f,8f,21f,22f);c.drawRoundRect(rect,1.2f,1.2f,p);rect.set(8f,2f,16f,14f);c.drawArc(rect,180f,180f,false,p);c.drawLine(8f,7f,8f,10f,p);c.drawLine(16f,7f,16f,10f,p) }
            ConsoleGlyph.RECENT -> { c.drawCircle(12f,12f,9f,p);c.drawLine(12f,6f,12f,12f,p);c.drawLine(12f,12f,17f,15f,p) }
            ConsoleGlyph.LIBRARY -> { c.drawRect(2f,3f,6f,21f,p);c.drawRect(10f,3f,14f,21f,p);val v=c.save();c.rotate(-12f,19f,12f);c.drawRect(18f,3f,22f,21f,p);c.restoreToCount(v) }
            ConsoleGlyph.SEARCH -> { c.drawCircle(10f,10f,7f,p);c.drawLine(15f,15f,22f,22f,p) }
            ConsoleGlyph.SETTINGS -> { c.drawPath(gear,p);c.drawCircle(12f,12f,3.2f,p) }
        }
        p.style=Paint.Style.FILL;c.restoreToCount(save)
    }
}

/** Reuses already-decoded catalog bitmaps; the dashboard tiles do not fetch a second set of artwork. */
internal class SeriesShortcutBackground(private val kind: Int): Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val icons=ConsoleIcons()
    private val dst=RectF()
    private val src=Rect()
    private val clip=Path()
    private var shade: Shader?=null
    private var pictures: List<Bitmap> = emptyList()
    private var focused=false
    fun mosaic(values: List<Drawable>) {
        val next=values.filterIsInstance<BitmapDrawable>().map { it.bitmap }.take(4)
        if(next==pictures) return
        pictures=next;invalidateSelf()
    }
    fun clear() { pictures=emptyList();invalidateSelf() }
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean {
        val next=android.R.attr.state_focused in state || android.R.attr.state_pressed in state
        if(next==focused) return false
        focused=next;invalidateSelf();return true
    }
    override fun onBoundsChange(b: Rect) {
        clip.reset();dst.set(b);dst.inset(3f,3f);clip.addRoundRect(dst,6f,6f,Path.Direction.CW)
        shade=LinearGradient(0f,0f,b.width().toFloat(),b.height().toFloat(),intArrayOf(0x50000000,0xdc000000.toInt()),null,Shader.TileMode.CLAMP)
    }
    override fun draw(c: Canvas) {
        val save=c.save();c.clipPath(clip);paint.style=Paint.Style.FILL;paint.shader=null;paint.alpha=255
        paint.color=when(kind) { 1 -> 0xff0c5717.toInt();2 -> 0xff193d4b.toInt();else -> 0xff17212a.toInt() };c.drawRect(bounds,paint)
        if(kind==0 && pictures.isNotEmpty()) {
            val width=bounds.width().toFloat()/pictures.size
            pictures.forEachIndexed { i,b ->
                dst.set(bounds.left+i*width,bounds.top.toFloat(),bounds.left+(i+1)*width,bounds.bottom.toFloat())
                val sourceWidth=(b.height*width/bounds.height()).toInt().coerceIn(1,b.width)
                src.set((b.width-sourceWidth)/2,0,(b.width+sourceWidth)/2,b.height)
                c.drawBitmap(b,src,dst,paint)
            }
        }
        paint.shader=shade;c.drawRect(bounds,paint);paint.shader=null
        val size=(bounds.height()*.31f).coerceAtMost(38f)
        icons.draw(c,when(kind) { 0 -> ConsoleGlyph.LIBRARY;1 -> ConsoleGlyph.GAME_PASS;2 -> ConsoleGlyph.PURCHASED;else -> ConsoleGlyph.SETTINGS },bounds.left+16f,bounds.top+13f,size)
        c.restoreToCount(save)
        if(focused) { dst.set(bounds);dst.inset(2.5f,2.5f);paint.style=Paint.Style.STROKE;paint.color=Color.WHITE;paint.strokeWidth=3f;c.drawRoundRect(dst,7f,7f,paint);paint.style=Paint.Style.FILL }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
