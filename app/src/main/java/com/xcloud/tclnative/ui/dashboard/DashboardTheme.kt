package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

internal object DashboardTheme {
    val violet = Color.rgb(194, 92, 245)
    val yellow = Color.rgb(255, 211, 64)
    val muted = Color.rgb(152, 150, 160)
    private var bold: Typeface? = null
    private var medium: Typeface? = null
    fun font(context: Context, heavy: Boolean = true): Typeface {
        if (heavy) return bold ?: Typeface.createFromAsset(context.assets, "fonts/BarlowCondensed-Bold.ttf").also { bold = it }
        return medium ?: Typeface.createFromAsset(context.assets, "fonts/BarlowCondensed-Medium.ttf").also { medium = it }
    }
}

internal class DashboardBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xff222423.toInt(), 0xff24212b.toInt(),
            0xff281633.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = 0xff302952.toInt()
        canvas.drawPath(Path().apply { moveTo(.16f*w, h); lineTo(.39f*w, .71f*h)
            lineTo(.56f*w, .71f*h); lineTo(.34f*w, h); close() }, paint)
        paint.shader = RadialGradient(w, h, .82f*w, intArrayOf(0x443e084d, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Concentric translucent strokes give the neon focus a soft edge without a software view layer. */
internal class FocusPlate(private val radius: Float, private val inset: Float = 0f) : Drawable() {
    var highlighted = false
        set(value) { field = value; invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val rect = RectF(bounds).apply { inset(inset, inset) }
        paint.style = Paint.Style.FILL
        paint.color = 0xff343436.toInt()
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (!highlighted) return
        paint.style = Paint.Style.STROKE
        for (i in 6 downTo 1) {
            paint.strokeWidth = i * 3f * (radius / 7f)
            paint.color = DashboardTheme.violet
            paint.alpha = 8 + (6 - i) * 2
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.strokeWidth = 3f * radius / 7f
        paint.color = DashboardTheme.violet; paint.alpha = 255
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

internal class RewardsMedal : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 32f, bounds.height() / 32f)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.color = 0xffb8e3ee.toInt()
        canvas.drawPath(Path().apply { moveTo(7f, 3f); lineTo(26f, 3f); lineTo(22f, 12f)
            lineTo(16f, 16f); lineTo(11f, 12f); close() }, paint)
        canvas.drawArc(2f, 3f, 12f, 14f, 90f, 190f, false, paint)
        canvas.drawArc(22f, 3f, 32f, 14f, -90f, 190f, false, paint)
        paint.style = Paint.Style.FILL; paint.color = DashboardTheme.yellow
        canvas.drawCircle(16f, 22f, 9f, paint)
        paint.color = 0xffbd8d12.toInt(); paint.textSize = 13f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("★", 16f, 27f, paint)
        canvas.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
