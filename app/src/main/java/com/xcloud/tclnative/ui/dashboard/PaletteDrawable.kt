package com.xcloud.tclnative.ui.dashboard

import android.graphics.drawable.GradientDrawable
import android.graphics.Canvas

internal interface PaletteDrawable { fun refreshPalette() }

/** Remember color roles, so existing hidden/loading views change without being recreated. */
internal class PaletteGradientDrawable(
    direction: Orientation, private val roles: Array<UiColor>,private val strokeWidth: Int=0,private val stroke: UiColor?=null
) : GradientDrawable(),PaletteDrawable {
    private var renderedPalette: UiPalette?=null
    init { orientation=direction;refreshPalette() }
    override fun refreshPalette() {
        if(roles.size==1) setColor(DashboardTheme.color(roles.single())) else setColors(roles.map { DashboardTheme.color(it) }.toIntArray())
        stroke?.let { setStroke(strokeWidth,DashboardTheme.color(it)) }
        renderedPalette=DashboardTheme.palette
        invalidateSelf()
    }
    override fun draw(canvas: Canvas) {
        if(renderedPalette!==DashboardTheme.palette) refreshPalette()
        super.draw(canvas)
    }
}
