package com.xcloud.tclnative.ui.dashboard

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

/** Pixel sizes are bucketed so adjacent cells and repeated layouts share thumbnails. */
internal data class ArtworkSize(val width: Int, val height: Int) {
    companion object {
        fun forDisplay(width: Int, height: Int): ArtworkSize {
            fun bucket(value: Int) = ((value.coerceIn(1,2048)+63)/64)*64
            return ArtworkSize(bucket(width),bucket(height))
        }
    }
    fun fit(sourceWidth: Int, sourceHeight: Int): ArtworkSize {
        val factor=minOf(1.0,width.toDouble()/sourceWidth,height.toDouble()/sourceHeight)
        return ArtworkSize((sourceWidth*factor).roundToInt().coerceAtLeast(1),
            (sourceHeight*factor).roundToInt().coerceAtLeast(1))
    }
}

internal data class ArtworkRequest(val url: String, val size: ArtworkSize) {
    companion object {
        fun create(original: String, width: Int, height: Int): ArtworkRequest {
            val size=ArtworkSize(width.coerceIn(1,2048),height.coerceIn(1,2048))
            val parsed=original.toHttpUrlOrNull()
            val url=if(parsed?.host in setOf("store-images.s-microsoft.com","store-images.microsoft.com"))
                parsed!!.newBuilder().setQueryParameter("w",size.width.toString()).setQueryParameter("h",size.height.toString())
                    .setQueryParameter("q",if(size.width<=512 && size.height<=768) "80" else "90").build().toString()
                else original
            return ArtworkRequest(url,size)
        }
    }
}

internal data class ArtworkPolicy(val memoryCacheBytes: Int, val parallelism: Int,
                                  val constrainedMemory: Boolean = false) {
    fun sizeFor(width: Int,height: Int): ArtworkSize {
        val size=ArtworkSize(width.coerceIn(1,2048),height.coerceIn(1,2048))
        if(!constrainedMemory) return size
        val factor=minOf(1.0,640.0/maxOf(size.width,size.height),
            kotlin.math.sqrt(230400.0/(size.width.toDouble()*size.height)))
        return ArtworkSize((size.width*factor).toInt().coerceAtLeast(1),
            (size.height*factor).toInt().coerceAtLeast(1))
    }
    companion object {
        fun forDevice(memoryMb: Int, lowRam: Boolean, processors: Int,
                      constrainedMemory: Boolean = lowRam || memoryMb in 1..128): ArtworkPolicy {
            if(constrainedMemory) return ArtworkPolicy(4*1024*1024,1,true)
            val modest=lowRam || memoryMb<=192 || processors<=4
            return ArtworkPolicy((memoryMb/16).coerceIn(4,if(modest) 12 else 20)*1024*1024,if(modest) 2 else 3)
        }
    }
}
