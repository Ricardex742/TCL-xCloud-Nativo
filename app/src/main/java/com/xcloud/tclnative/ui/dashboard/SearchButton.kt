package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Vector magnifier with a larger transparent touch target than its visible plate. */
internal class SearchButton(context: Context,search: () -> Unit): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        id=generateViewId(); contentDescription=UiText.text("Pesquisar jogos")
        isFocusable=true; isFocusableInTouchMode=true; isClickable=true
        DashboardTheme.customFocus(this)
        setOnClickListener { search() }
        setOnFocusChangeListener { _,_ -> invalidate() }
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size=min(width,height).toFloat(); val cx=width/2f; val cy=height/2f
        val radius=size*.32f
        paint.style=Paint.Style.FILL; paint.color=if(isPressed || isFocused) DashboardTheme.color(UiColor.SEARCH_ACTIVE) else DashboardTheme.color(UiColor.SEARCH_FILL)
        canvas.drawRoundRect(cx-radius,cy-radius,cx+radius,cy+radius,size*.12f,size*.12f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=size*.025f
        paint.color=if(isPressed || isFocused) DashboardTheme.accent else DashboardTheme.color(UiColor.SEARCH_BORDER)
        canvas.drawRoundRect(cx-radius,cy-radius,cx+radius,cy+radius,size*.12f,size*.12f,paint)
        paint.color=Color.WHITE; paint.strokeCap=Paint.Cap.ROUND; paint.strokeWidth=size*.036f
        canvas.drawCircle(cx-size*.04f,cy-size*.04f,size*.125f,paint)
        canvas.drawLine(cx+size*.05f,cy+size*.05f,cx+size*.16f,cy+size*.16f,paint)
    }
}
