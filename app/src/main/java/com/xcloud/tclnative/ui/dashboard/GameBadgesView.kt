package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.xcloud.tclnative.R
import kotlin.math.roundToInt

/** Badges describe the selected Store product, never inferred from the user's subscription. */
internal class GameBadgesView(context: Context) : FrameLayout(context) {
    private var geometryScale=-1f
    private fun badge(resource: Int, description: String)=ImageView(context).apply {
        setImageResource(resource)
        scaleType=ImageView.ScaleType.FIT_CENTER
        background=GradientDrawable().apply { setColor(Color.WHITE);cornerRadius=2f }
        contentDescription=description
        isFocusable=false
        isClickable=false
    }
    private val pass=badge(R.drawable.badge_game_pass,UiText.text("Disponível no Game Pass"))
    private val series=badge(R.drawable.badge_series_xs,UiText.text("Otimizado para Xbox Series X|S"))
    init {
        isFocusable=false
        background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,0xcf000000.toInt()))
        addView(pass);addView(series)
    }
    fun bind(gamePass: Boolean, optimized: Boolean, scale: Float) {
        visibility=if(gamePass || optimized) View.VISIBLE else View.GONE
        pass.visibility=if(gamePass) View.VISIBLE else View.GONE
        series.visibility=if(optimized) View.VISIBLE else View.GONE
        val offset=((if(gamePass) 49 else 8)*scale).roundToInt()
        (series.layoutParams as? LayoutParams)?.let { params ->
            if(geometryScale==scale && params.leftMargin!=offset) {
                params.leftMargin=offset;series.layoutParams=params
            }
        }
        if(geometryScale==scale) return
        geometryScale=scale
        val padding=(2*scale).roundToInt()
        pass.setPadding(padding,padding,padding,padding)
        series.setPadding(padding,padding,padding,padding)
        pass.layoutParams=LayoutParams((36*scale).roundToInt(),(25*scale).roundToInt(),Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin=(8*scale).roundToInt();bottomMargin=(8*scale).roundToInt() }
        series.layoutParams=LayoutParams((54*scale).roundToInt(),(25*scale).roundToInt(),Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin=offset;bottomMargin=(8*scale).roundToInt() }
    }
}
