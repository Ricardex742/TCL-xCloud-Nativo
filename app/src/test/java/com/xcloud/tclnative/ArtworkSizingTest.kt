package com.xcloud.tclnative

import com.xcloud.tclnative.ui.dashboard.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ArtworkSizingTest {
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
