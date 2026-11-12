package com.xcloud.tclnative.ui.dashboard

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.*
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToInt

/** Draw the original vector outlines at the current canvas scale, without a shared raster cache. */
internal class CatalogMarkDrawable(resources: Resources, resource: Int): Drawable() {
    private val geometry=geometry(resources,resource)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var tint: ColorStateList?=null
    private var opacity=255
    override fun getIntrinsicWidth()=geometry.width.roundToInt()
    override fun getIntrinsicHeight()=geometry.height.roundToInt()
    override fun draw(canvas: Canvas) {
        if(bounds.isEmpty) return
        val scale=minOf(bounds.width()/geometry.width,bounds.height()/geometry.height)
        val saved=canvas.save()
        canvas.translate(bounds.left+(bounds.width()-geometry.width*scale)/2,bounds.top+(bounds.height()-geometry.height*scale)/2)
        canvas.scale(scale,scale)
        paint.color=tint?.getColorForState(state,Color.WHITE) ?: Color.WHITE
        paint.alpha=opacity
        geometry.paths.forEach { canvas.drawPath(it,paint) }
        canvas.restoreToCount(saved)
    }
    override fun setTintList(tint: ColorStateList?) { this.tint=tint;invalidateSelf() }
    override fun isStateful()=tint?.isStateful==true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf();return isStateful }
    override fun setAlpha(alpha: Int) { opacity=alpha.coerceIn(0,255);invalidateSelf() }
    override fun getAlpha()=opacity
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter;invalidateSelf() }
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    private data class Geometry(val width: Float,val height: Float,val paths: List<Path>)
    companion object {
        private const val ANDROID="http://schemas.android.com/apk/res/android"
        private val cache=mutableMapOf<Int,Geometry>()
        @Synchronized private fun geometry(resources: Resources,resource: Int): Geometry = cache.getOrPut(resource) {
            // Geometry is immutable and shared; colors, alpha and canvas transforms belong to each drawable.
            val xml=resources.getXml(resource)
            try {
                var width=0f;var height=0f;val paths=mutableListOf<Path>()
                while(xml.eventType!=XmlPullParser.END_DOCUMENT) {
                    if(xml.eventType==XmlPullParser.START_TAG) when(xml.name) {
                        "vector" -> { width=xml.getAttributeFloatValue(ANDROID,"viewportWidth",0f);height=xml.getAttributeFloatValue(ANDROID,"viewportHeight",0f) }
                        "path" -> paths+=requireNotNull(PathParser.createPathFromPathData(xml.getAttributeValue(ANDROID,"pathData")))
                    }
                    xml.next()
                }
                require(width>0 && height>0 && paths.isNotEmpty())
                Geometry(width,height,paths.toList())
            } finally { xml.close() }
        }
    }
}
