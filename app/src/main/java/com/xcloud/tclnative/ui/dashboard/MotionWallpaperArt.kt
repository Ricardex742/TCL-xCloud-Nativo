package com.xcloud.tclnative.ui.dashboard

import android.graphics.drawable.Drawable
import android.graphics.*
import com.xcloud.tclnative.settings.SeriesWallpaper

internal abstract class MotionWallpaperArt: Drawable() {
    abstract var phase: Float
    var lowDetail=false
    var hasArtwork=false
    var animationEnabled=true
    private val imagePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageBounds=RectF()
    open fun drawArtwork(canvas: Canvas,bitmap: Bitmap,width: Int,height: Int) {
        val fit=maxOf(width.toFloat()/bitmap.width,height.toFloat()/bitmap.height)
        val w=bitmap.width*fit;val h=bitmap.height*fit
        imageBounds.set((width-w)/2,(height-h)/2,(width+w)/2,(height+h)/2)
        canvas.drawBitmap(bitmap,null,imageBounds,imagePaint)
    }
    companion object {
        fun create(entry: SeriesWallpaper): MotionWallpaperArt = entry.recreation?.let { RecreatedWallpaperArt(it) }
            ?: SeriesWallpaperArt(entry.style)
    }
}
