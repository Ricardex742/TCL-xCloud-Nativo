package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.widget.TextView
import android.util.TypedValue
import android.view.Gravity

internal object DashboardTheme {
    val accent = Color.rgb(84, 222, 105)
    val yellow = Color.rgb(255, 211, 64)
    val muted = Color.rgb(166, 184, 171)
    val surface = 0xff202a24.toInt()
    val border = 0xff3a5944.toInt()
    fun customFocus(view: View) {
        if (Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
    }
    fun panel(radius: Float = 12f) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0xff202c24.toInt(), 0xff151d18.toInt())).apply {
        cornerRadius = radius; setStroke(1, border)
    }
    fun button(context: Context, label: String, scale: Float = 1f, action: () -> Unit): TextView = TextView(context).apply {
        text = label; typeface = font(context); includeFontPadding = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 23*scale); setTextColor(Color.WHITE)
        gravity = Gravity.CENTER; maxLines = 2
        isFocusable = true; isFocusableInTouchMode = true; isClickable = true
        customFocus(this)
        val plate = FocusPlate(8*scale, 5*scale)
        background = plate
        setPadding((15*scale).toInt(), (9*scale).toInt(), (15*scale).toInt(), (9*scale).toInt())
        setOnFocusChangeListener { _, focused -> plate.highlighted = focused }
        setOnClickListener { if (isEnabled) action() }
    }
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
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xff1b211d.toInt(), 0xff18261e.toInt(),
            0xff102c1a.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = 0xff244832.toInt()
        canvas.drawPath(Path().apply { moveTo(.16f*w, h); lineTo(.39f*w, .71f*h)
            lineTo(.56f*w, .71f*h); lineTo(.34f*w, h); close() }, paint)
        paint.shader = RadialGradient(w, h, .82f*w, intArrayOf(0x443c994d, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
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
        paint.color = DashboardTheme.surface
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (!highlighted) return
        paint.style = Paint.Style.STROKE
        for (i in 6 downTo 1) {
            paint.strokeWidth = i * 3f * (radius / 7f)
            paint.color = DashboardTheme.accent
            paint.alpha = 8 + (6 - i) * 2
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
        paint.strokeWidth = 3f * radius / 7f
        paint.color = DashboardTheme.accent; paint.alpha = 255
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
