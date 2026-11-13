package com.xcloud.tclnative

import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ArtworkSizingTest {
    @Test fun oneGbBudgetCapsLargeImagesBeforeDownloadingButKeepsThumbnailDetail() {
        val policy=ArtworkPolicy.forDevice(256,false,4,constrainedMemory=true)
        assertEquals(1,policy.parallelism)
        assertEquals(4*1024*1024,policy.memoryCacheBytes)
        for((w,h) in listOf(3840 to 2160,720 to 1080,1024 to 1024,1 to 2048)) {
            val size=policy.sizeFor(w,h)
            assertTrue(size.width*size.height<=230400)
            assertTrue(maxOf(size.width,size.height)<=640)
            val request=ArtworkRequest.create("https://store-images.s-microsoft.com/art",size.width,size.height)
            assertEquals(size.width.toString(),request.url.toHttpUrl().queryParameter("w"))
            assertEquals(size.height.toString(),request.url.toHttpUrl().queryParameter("h"))
        }
        assertEquals(ArtworkSize(192,320),policy.sizeFor(192,320))
        assertEquals(ArtworkSize(1920,1080),ArtworkPolicy.forDevice(512,false,8).sizeFor(1920,1080))
    }
    @Test fun phoneCoversRequestSmallStableSizesAndRetainThePublicImageParameters() {
        val size=ArtworkSize.forDisplay(191,290)
        assertEquals(size,ArtworkSize.forDisplay(190,289))
        assertTrue(size.width in 191..254 && size.height in 290..353)
        val request=ArtworkRequest.create("https://store-images.s-microsoft.com/image/apps.cover?w=720&h=1080&format=jpg",size.width,size.height)
        val url=request.url.toHttpUrl()
        assertEquals(size.width.toString(),url.queryParameter("w")); assertEquals(size.height.toString(),url.queryParameter("h"))
        assertEquals("jpg",url.queryParameter("format")); assertEquals("80",url.queryParameter("q"))
        val fit=size.fit(720,1080)
        assertTrue(fit.width<=size.width && fit.height<=size.height)
        assertEquals(2.0/3,fit.width.toDouble()/fit.height,.01)
        val avatar="https://avatar.example/profile.png?size=96"
        assertEquals(avatar,ArtworkRequest.create(avatar,128,128).url)
        assertNotEquals(ArtworkRequest.create(avatar,128,128),ArtworkRequest.create(avatar,256,256))
    }
    @Test fun modestDevicesUseABoundedBackgroundBudgetAndSmallCellsDoNotRequestFullPosters() {
        for(policy in listOf(ArtworkPolicy.forDevice(128,false,8),ArtworkPolicy.forDevice(512,true,8),ArtworkPolicy.forDevice(256,false,4))) {
            assertTrue(policy.parallelism<=2)
            assertTrue(policy.memoryCacheBytes in 4*1024*1024..12*1024*1024)
        }
        val phone=ArtworkSize.forDisplay(191,290)
        assertTrue(phone.width*phone.height<720*1080/8)
        assertTrue(ArtworkPolicy.forDevice(512,false,8).parallelism<=3)
    }
}
