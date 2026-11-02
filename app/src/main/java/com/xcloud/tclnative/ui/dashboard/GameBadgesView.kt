package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Badges describe the selected Store product, never inferred from the user's subscription. */
internal class GameBadgesView(context: Context) : FrameLayout(context) {
    private val pass = TextView(context).apply {
        text = "GAME\nPASS"; gravity = Gravity.CENTER; includeFontPadding = false
        typeface = DashboardTheme.font(context); setTextColor(Color.BLACK)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 2f }
        contentDescription = "Disponível no Game Pass"
    }
    private val series = TextView(context).apply {
        text = "X|S"; includeFontPadding = false; gravity = Gravity.CENTER
        typeface = DashboardTheme.font(context); setTextColor(Color.WHITE)
        contentDescription = "Otimizado para Xbox Series X|S"
    }
    init {
        isFocusable = false
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xcf000000.toInt()))
        addView(pass); addView(series)
    }
    fun bind(gamePass: Boolean, optimized: Boolean, scale: Float) {
        visibility = if (gamePass || optimized) View.VISIBLE else View.GONE
        pass.visibility = if (gamePass) View.VISIBLE else View.GONE
        series.visibility = if (optimized) View.VISIBLE else View.GONE
        pass.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13*scale)
        pass.setLineSpacing(0f, .85f)
        series.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24*scale)
        pass.layoutParams = LayoutParams((33*scale).roundToInt(), (27*scale).roundToInt(), Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin = (9*scale).roundToInt(); bottomMargin = (9*scale).roundToInt() }
        series.layoutParams = LayoutParams((39*scale).roundToInt(), (29*scale).roundToInt(), Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin = ((if (gamePass) 47 else 8)*scale).roundToInt(); bottomMargin = (8*scale).roundToInt() }
    }
}
