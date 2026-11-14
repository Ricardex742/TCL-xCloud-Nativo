package com.xcloud.tclnative.ui.dashboard

import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.text.TextUtils
import com.xcloud.tclnative.settings.*

object StatisticsOverlayStyle {
    fun apply(view: TextView,settings: StatisticsSettings) {
        val value=settings.normalized();val density=view.resources.displayMetrics.density
        val margin=(12*density).toInt()
        val gravity=when(value.position) {
            StatisticsPosition.TOP_LEFT -> Gravity.TOP or Gravity.START
            StatisticsPosition.TOP_RIGHT -> Gravity.TOP or Gravity.END
            StatisticsPosition.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            StatisticsPosition.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,value.textSize.toFloat())
        view.includeFontPadding=false
        view.ellipsize=TextUtils.TruncateAt.END
        view.setBackgroundColor(Color.argb(value.background*255/100,0,0,0))
        view.setPadding((8*density).toInt(),(6*density).toInt(),(8*density).toInt(),(6*density).toInt())
        view.layoutParams=FrameLayout.LayoutParams(-2,-2,gravity).apply {
            topMargin=(56*density).toInt();bottomMargin=margin;marginStart=margin;marginEnd=margin
        }
    }
    fun constrain(view: TextView,width: Int,height: Int) {
        val margins=view.layoutParams as? FrameLayout.LayoutParams ?: return
        val maxWidth=(width-margins.marginStart-margins.marginEnd).coerceAtLeast(1)
        val available=(height-margins.topMargin-margins.bottomMargin-view.compoundPaddingTop-view.compoundPaddingBottom).coerceAtLeast(1)
        val lines=(available/view.lineHeight.coerceAtLeast(1)).coerceAtLeast(1)
        if(view.maxWidth!=maxWidth) view.maxWidth=maxWidth
        if(view.maxLines!=lines) view.maxLines=lines
    }
}
