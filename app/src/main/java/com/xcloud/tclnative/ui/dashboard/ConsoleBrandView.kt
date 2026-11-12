package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.ConsoleScene
import kotlin.math.roundToInt

/** Original brand artwork, with monochrome variants on dark surfaces; never a font approximation. */
internal class ConsoleBrandView(context: Context): FrameLayout(context) {
    private val mark=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val word=ImageView(context).apply { scaleType=ImageView.ScaleType.FIT_CENTER;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var scene=ConsoleScene.NONE
    private var geometry=0L
    init { isFocusable=false;addView(mark);addView(word);refresh() }
    fun refresh() {
        scene=DashboardTheme.palette.theme.scene;contentDescription=DashboardTheme.palette.theme.label
        mark.imageTintList=null;word.imageTintList=null;word.visibility=View.GONE
        when(scene) {
            ConsoleScene.XBOX_360,ConsoleScene.XBOX_360_NXE,ConsoleScene.XBOX_360_METRO -> {
                mark.setImageResource(R.drawable.console_logo_360_sphere);word.setImageResource(R.drawable.console_logo_360_wordmark);word.visibility=View.VISIBLE
                word.imageTintList=ColorStateList.valueOf(if(scene==ConsoleScene.XBOX_360_NXE) Color.WHITE else 0xff4c583c.toInt())
            }
            ConsoleScene.XBOX_ONE -> { mark.setImageResource(R.drawable.console_logo_one);mark.imageTintList=ColorStateList.valueOf(Color.WHITE) }
            ConsoleScene.XBOX_SERIES -> {
                mark.setImageResource(R.drawable.console_logo_xbox_wordmark);word.setImageResource(R.drawable.badge_series_xs);word.visibility=View.VISIBLE
                mark.imageTintList=ColorStateList.valueOf(Color.WHITE);word.imageTintList=ColorStateList.valueOf(Color.WHITE)
            }
            ConsoleScene.XBOX_ORIGINAL -> mark.setImageResource(R.drawable.console_logo_original)
            ConsoleScene.NONE -> mark.setImageResource(R.drawable.brand_logo)
        }
        geometry=0L;requestLayout();invalidate()
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val w=View.MeasureSpec.getSize(widthMeasureSpec);val h=View.MeasureSpec.getSize(heightMeasureSpec)
        val key=(w.toLong() shl 32) or h.toLong()
        if(key!=geometry) { geometry=key;layoutArtwork(w,h) }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    private fun layoutArtwork(w: Int,h: Int) {
        fun item(v: View,x: Float,y: Float,width: Float,height: Float) {
            v.layoutParams=LayoutParams(width.roundToInt(),height.roundToInt()).apply { leftMargin=x.roundToInt();topMargin=y.roundToInt() }
        }
        if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_360_NXE || scene==ConsoleScene.XBOX_360_METRO) {
            val ball=minOf(h.toFloat(),w*.25f);val gap=ball*.15f
            item(mark,0f,0f,ball,h.toFloat());item(word,ball+gap,0f,w-ball-gap,h.toFloat())
        } else if(scene==ConsoleScene.XBOX_SERIES) {
            item(mark,0f,0f,w*.46f,h.toFloat());item(word,w*.52f,0f,w*.48f,h.toFloat())
        } else item(mark,0f,0f,w.toFloat(),h.toFloat())
    }
}
