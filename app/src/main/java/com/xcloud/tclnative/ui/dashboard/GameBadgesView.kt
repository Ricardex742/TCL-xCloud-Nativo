package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import com.xcloud.tclnative.R
import com.xcloud.tclnative.settings.ConsoleScene
import com.xcloud.tclnative.settings.UiTheme
import kotlin.math.roundToInt

/** Badges describe the selected Store product, never inferred from the user's subscription. */
internal class GameBadgesView(context: Context) : FrameLayout(context) {
    private var geometryScale=-1f
    private var renderedTheme: UiTheme?=null
    private val crop=Matrix()
    private fun badge(resource: Int, description: String)=ImageView(context).apply {
        setImageResource(resource)
        scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription=description
        isFocusable=false
        isClickable=false
    }
    private val pass=badge(R.drawable.badge_game_pass,UiText.text("Disponível no Game Pass"))
    private val series=badge(R.drawable.badge_series_xs,UiText.text("Otimizado para Xbox Series X|S"))
    init {
        isFocusable=false
        addView(pass);addView(series)
        pass.scaleType=ImageView.ScaleType.MATRIX;pass.cropToPadding=true
    }
    fun bind(gamePass: Boolean, optimized: Boolean, scale: Float) {
        visibility=if(gamePass || optimized) View.VISIBLE else View.GONE
        pass.visibility=if(gamePass) View.VISIBLE else View.GONE
        series.visibility=if(optimized) View.VISIBLE else View.GONE
        val offset=((if(gamePass) 84 else 8)*scale).roundToInt()
        (series.layoutParams as? LayoutParams)?.let { params ->
            if(geometryScale==scale && params.leftMargin!=offset) {
                params.leftMargin=offset;series.layoutParams=params
            }
        }
        if(geometryScale==scale && renderedTheme==DashboardTheme.palette.theme) return
        geometryScale=scale
        renderedTheme=DashboardTheme.palette.theme
        val scene=renderedTheme!!.scene
        val light=scene==ConsoleScene.XBOX_360
        val ink=if(light) 0xff35412b.toInt() else if(scene==ConsoleScene.XBOX_ORIGINAL) 0xffb6ec57.toInt() else Color.WHITE
        val colors=when(scene) {
            ConsoleScene.XBOX_360 -> intArrayOf(0xf4f2f5e4.toInt(),0xf4cbd3b9.toInt())
            ConsoleScene.XBOX_360_NXE -> intArrayOf(0xe14e5e43.toInt(),0xe1273620.toInt())
            ConsoleScene.XBOX_360_METRO -> intArrayOf(0xec137e0b.toInt(),0xec137e0b.toInt())
            ConsoleScene.XBOX_ONE -> intArrayOf(0xe71a1a1a.toInt(),0xe71a1a1a.toInt())
            ConsoleScene.XBOX_SERIES -> intArrayOf(0xd91c2428.toInt(),0xd91c2428.toInt())
            ConsoleScene.XBOX_ORIGINAL -> intArrayOf(0xec102b03.toInt(),0xec071500.toInt())
            else -> intArrayOf(DashboardTheme.surface,DashboardTheme.surface)
        }
        val radius=when(scene) { ConsoleScene.XBOX_360 -> 9f;ConsoleScene.XBOX_ORIGINAL -> 10f;ConsoleScene.XBOX_ONE,ConsoleScene.XBOX_360_METRO -> 0f;else -> 4f }*scale
        for(view in listOf(pass,series)) {
            view.imageTintList=ColorStateList.valueOf(ink)
            view.background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,colors).apply {
                cornerRadius=radius
                if(scene==ConsoleScene.XBOX_360 || scene==ConsoleScene.XBOX_ORIGINAL) setStroke(scale.roundToInt().coerceAtLeast(1),if(light) 0xff839266.toInt() else 0xff6e982a.toInt())
            }
        }
        pass.setPadding((3*scale).roundToInt(),(5*scale).roundToInt(),(3*scale).roundToInt(),(5*scale).roundToInt())
        series.setPadding((3*scale).roundToInt(),(3*scale).roundToInt(),(3*scale).roundToInt(),(3*scale).roundToInt())
        pass.layoutParams=LayoutParams((70*scale).roundToInt(),(24*scale).roundToInt(),Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin=(8*scale).roundToInt();bottomMargin=(6*scale).roundToInt() }
        series.layoutParams=LayoutParams((42*scale).roundToInt(),(24*scale).roundToInt(),Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin=offset;bottomMargin=(6*scale).roundToInt() }
    }
    override fun onLayout(changed: Boolean,left: Int,top: Int,right: Int,bottom: Int) {
        super.onLayout(changed,left,top,right,bottom)
        // Show the original GAME PASS wordmark, without squeezing the stacked Xbox orb into a tiny badge.
        val width=pass.width-pass.paddingLeft-pass.paddingRight
        val height=pass.height-pass.paddingTop-pass.paddingBottom
        if(width<=0 || height<=0) return
        val factor=minOf(width/330f,height/48f)
        crop.setScale(factor,factor)
        crop.postTranslate((width-330*factor)/2f,(height-48*factor)/2f-184*factor)
        pass.imageMatrix=crop
    }
}
