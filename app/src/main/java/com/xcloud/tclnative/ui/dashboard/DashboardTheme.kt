package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ProgressBar
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.AppearanceSettingsStore
import com.xcloud.tclnative.settings.UiTheme

internal object DashboardTheme {
    var palette=UiPalette(UiTheme.GREEN)
        private set
    fun color(role: UiColor)=palette[role]
    val accent get()=color(UiColor.ACCENT)
    val yellow = Color.rgb(255, 211, 64)
    val muted get()=color(UiColor.MUTED)
    val surface get()=color(UiColor.SURFACE)
    val border get()=color(UiColor.BORDER)
    private val overlay get()=when(palette.theme) {
        UiTheme.GREEN -> R.style.PaletteGreen
        UiTheme.CYBER_BLUE -> R.style.PaletteBlue
        UiTheme.VOLCANIC_RED -> R.style.PaletteRed
        UiTheme.PRESTIGE_GOLD -> R.style.PaletteGold
        UiTheme.INDUSTRIAL_ORANGE -> R.style.PaletteOrange
        UiTheme.GALAXY_PURPLE -> R.style.PalettePurple
        UiTheme.PLATINUM_SILVER -> R.style.PaletteSilver
    }
    fun install(context: Context) { apply(context,AppearanceSettingsStore(context).read()) }
    fun applyOverlay(context: Context) { context.theme.applyStyle(overlay,true) }
    fun apply(context: Context,theme: UiTheme,root: View?=null) {
        val previous=palette
        palette=UiPalette(theme)
        applyOverlay(context)
        (context as? Activity)?.window?.setBackgroundDrawable(ColorDrawable(color(UiColor.WINDOW_BACKGROUND)))
        root?.let { refreshView(it,previous,palette) }
    }
    private fun refreshView(view: View,previous: UiPalette,next: UiPalette) {
        fun replace(color: Int)=previous.replacement(color,next)
        fun drawable(value: Drawable?) {
            when(value) {
                is PaletteDrawable -> value.refreshPalette()
                is LayerDrawable -> repeat(value.numberOfLayers) { drawable(value.getDrawable(it)) }
            }
        }
        drawable(view.background)
        if(view is TextView) {
            view.setTextColor(replace(view.currentTextColor))
            view.setHintTextColor(replace(view.currentHintTextColor))
            view.highlightColor=replace(view.highlightColor)
            val text=view.text
            if(text is Spanned) {
                val updated=SpannableStringBuilder(text);var changed=false
                for(span in text.getSpans(0,text.length,ForegroundColorSpan::class.java)) {
                    val value=replace(span.foregroundColor)
                    if(value!=span.foregroundColor) {
                        updated.removeSpan(span)
                        updated.setSpan(ForegroundColorSpan(value),text.getSpanStart(span),text.getSpanEnd(span),text.getSpanFlags(span))
                        changed=true
                    }
                }
                if(changed) view.text=updated
            }
        }
        if(view is ProgressBar) {
            view.indeterminateTintList?.let { view.indeterminateTintList=ColorStateList.valueOf(replace(it.defaultColor)) }
            view.progressTintList?.let { view.progressTintList=ColorStateList.valueOf(replace(it.defaultColor)) }
        }
        view.invalidate()
        if(view is ViewGroup) repeat(view.childCount) { refreshView(view.getChildAt(it),previous,next) }
    }
    fun customFocus(view: View) {
        if (Build.VERSION.SDK_INT >= 26) view.defaultFocusHighlightEnabled = false
    }
    fun gradient(direction: GradientDrawable.Orientation,colors: Array<UiColor>,strokeWidth: Int=0,stroke: UiColor?=null)=
        PaletteGradientDrawable(direction,colors,strokeWidth,stroke)
    fun shape(fill: UiColor,radius: Float,strokeWidth: Int=0,stroke: UiColor?=null)=
        gradient(GradientDrawable.Orientation.TOP_BOTTOM,arrayOf(fill),strokeWidth,stroke).apply { cornerRadius=radius }
    fun panel(radius: Float = 12f)=gradient(GradientDrawable.Orientation.TL_BR,
        arrayOf(UiColor.PANEL_TOP,UiColor.PANEL_BOTTOM),1,UiColor.BORDER).apply { cornerRadius=radius }
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

internal class DashboardBackground : Drawable(),PaletteDrawable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var gradient: Shader?=null
    private var glow: Shader?=null
    private val ribbon=Path()
    private var renderedPalette: UiPalette?=null
    override fun refreshPalette() { onBoundsChange(bounds);invalidateSelf() }
    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        if(w<=0 || h<=0) return
        gradient = LinearGradient(0f, 0f, w, h, intArrayOf(DashboardTheme.color(UiColor.BACKGROUND_START),
            DashboardTheme.color(UiColor.BACKGROUND_MID),DashboardTheme.color(UiColor.BACKGROUND_END)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        ribbon.reset(); ribbon.moveTo(.16f*w,h); ribbon.lineTo(.39f*w,.71f*h)
        ribbon.lineTo(.56f*w,.71f*h); ribbon.lineTo(.34f*w,h); ribbon.close()
        glow=RadialGradient(w,h,.82f*w,intArrayOf(DashboardTheme.color(UiColor.BACKGROUND_GLOW),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
        renderedPalette=DashboardTheme.palette
    }
    override fun draw(canvas: Canvas) {
        if(renderedPalette!==DashboardTheme.palette) onBoundsChange(bounds)
        paint.shader=gradient
        canvas.drawRect(bounds, paint)
        paint.shader = null
        paint.color = DashboardTheme.color(UiColor.BACKGROUND_RIBBON)
        canvas.drawPath(ribbon, paint)
        paint.shader=glow
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
