package org.webrtc

import android.media.MediaFormat
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RealtimeDecoderCodecTest {
    private class Driver {
        var configured: MediaFormat? = null
        var configureFailure: RuntimeException? = null
        var startFailure: RuntimeException? = null
        val codec = Proxy.newProxyInstance(MediaCodecWrapper::class.java.classLoader,
            arrayOf(MediaCodecWrapper::class.java)) { _, method, args ->
            when (method.name) {
                "configure" -> {
                    configured = args[0] as MediaFormat
                    configureFailure?.let { throw it }
                }
                "start" -> startFailure?.let { throw it }
            }
            null
        } as MediaCodecWrapper
    }

    private fun format() = MediaFormat.createVideoFormat("video/avc", 2080, 992)

    @Test fun realtimePriorityDoesNotRequireLowLatencySupportOrChangeTheVideoFormat() {
        val driver = Driver(); val status = mutableListOf<String>()
        val codec = RealtimeDecoderCodec(driver.codec, false, status::add)
        codec.configure(format(), null, null, 0)
        codec.start()
        val actual = driver.configured!!
        assertEquals(0, actual.getInteger(MediaFormat.KEY_PRIORITY))
        assertFalse(actual.containsKey(MediaFormat.KEY_LOW_LATENCY))
        assertFalse(actual.containsKey(MediaFormat.KEY_OPERATING_RATE))
        assertEquals("video/avc", actual.getString(MediaFormat.KEY_MIME))
        assertEquals(2080, actual.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(992, actual.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue(status.single().contains("prioridade de tempo real solicitada"))
        assertTrue(status.single().contains("LL: não solicitado"))
    }

    @Test fun declaredLowLatencySupportAddsThePublicHintOnAndroid11() {
        val driver = Driver(); val status = mutableListOf<String>()
        val codec = RealtimeDecoderCodec(driver.codec, true, status::add)
        codec.configure(format(), null, null, 0)
        assertTrue(status.isEmpty()) // Configuration alone does not mean playback started.
        codec.start()
        assertEquals(1, driver.configured!!.getInteger(MediaFormat.KEY_LOW_LATENCY))
        assertTrue(status.single().contains("LL: solicitado"))
    }

    @Test @Config(sdk = [24])
    fun android7RequestsPriorityWithoutUsingTheAndroid11Key() {
        val driver = Driver()
        RealtimeDecoderCodec(driver.codec, true) {}.configure(format(), null, null, 0)
        assertEquals(0, driver.configured!!.getInteger(MediaFormat.KEY_PRIORITY))
        assertFalse(driver.configured!!.containsKey("low-latency"))
    }

    @Test fun rejectedConfigurationUsesTheFailurePathCaughtByThePinnedWebRtcDecoder() {
        val driver = Driver().apply { configureFailure = UnsupportedOperationException("driver") }
        val status = mutableListOf<String>()
        try {
            RealtimeDecoderCodec(driver.codec, true, status::add).configure(format(), null, null, 0)
            fail("The decoder must fall back instead of claiming success")
        } catch (e: IllegalStateException) {
            assertSame(driver.configureFailure, e.cause)
        }
        assertTrue(status.single().contains("rejeitadas"))
    }

    @Test fun rejectedStartDoesNotReportRealtimePlaybackAsActive() {
        val driver = Driver().apply { startFailure = IllegalArgumentException("start") }
        val status = mutableListOf<String>()
        val codec = RealtimeDecoderCodec(driver.codec, true, status::add)
        codec.configure(format(), null, null, 0)
        try { codec.start(); fail("Start must fail") }
        catch (e: IllegalStateException) { assertSame(driver.startFailure, e.cause) }
        assertTrue(status.single().contains("rejeitado"))
    }
}
