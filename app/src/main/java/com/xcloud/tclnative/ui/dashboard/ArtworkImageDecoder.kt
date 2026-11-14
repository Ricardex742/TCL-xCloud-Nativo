package com.xcloud.tclnative.ui.dashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Decode from disk with sampling before allocating pixels; never keep a compressed image copy in heap. */
internal object ArtworkImageDecoder {
    fun decode(file: File,size: ArtworkSize): Bitmap? {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        val width=bounds.outWidth;val height=bounds.outHeight
        if(width !in 1..32768 || height !in 1..32768 || width.toLong()*height>128_000_000L) return null
        val target=size.fit(width,height)
        var sample=1
        while(width/(sample*2)>=target.width && height/(sample*2)>=target.height) sample*=2
        val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply {
            inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565
        }) ?: return null
        var transferred=false
        try {
            val fitted=if(decoded.width>size.width || decoded.height>size.height) {
                val fit=size.fit(decoded.width,decoded.height)
                Bitmap.createScaledBitmap(decoded,fit.width,fit.height,true)
            } else decoded
            transferred=fitted===decoded
            return fitted
        } finally {
            if(!transferred) decoded.recycle()
        }
    }
}
