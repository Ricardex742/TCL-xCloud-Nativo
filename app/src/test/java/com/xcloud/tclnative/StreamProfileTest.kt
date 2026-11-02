package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.StreamProfile
import com.xcloud.tclnative.renderer.DisplayModePolicy
import com.xcloud.tclnative.renderer.OutputMode
import org.junit.Assert.*
import org.junit.Test

class StreamProfileTest {
    private val sdp = """
        v=0
        m=audio 9 UDP/TLS/RTP/SAVPF 111
        c=IN IP4 0.0.0.0
        b=AS:128
        a=rtpmap:111 opus/48000/2
        a=fmtp:111 useinbandfec=1
        m=video 9 UDP/TLS/RTP/SAVPF 96 97
        c=IN IP4 0.0.0.0
        b=AS:15000
        b=TIAS:15000000
        a=rtpmap:96 H264/90000
        a=fmtp:96 profile-level-id=640c1f;packetization-mode=1;level-asymmetry-allowed=1
        a=rtpmap:97 rtx/90000
        a=fmtp:97 apt=96
        a=ice-ufrag:keep-ufrag
        a=fingerprint:sha-256 keep-fingerprint
        m=application 9 UDP/DTLS/SCTP webrtc-datachannel
        a=sctp-port:5000
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    @Test fun videoOfferRequestsTwentyMbpsFloorWithoutChangingAudioOrDecoderProfile() {
        val result = StreamProfile.receiveSdp(sdp)
        assertTrue(result.contains("b=AS:30000\r\nb=TIAS:30000000"))
        assertFalse(result.contains("b=AS:15000"))
        assertTrue(result.contains("x-google-min-bitrate=20000"))
        assertTrue(result.contains("x-google-start-bitrate=20000"))
        assertTrue(result.contains("x-google-max-bitrate=30000"))
        assertEquals(sdp.substringBefore("m=video"), result.substringBefore("m=video"))
        assertEquals(sdp.substringAfter("m=application"), result.substringAfter("m=application"))
        assertTrue(result.contains("profile-level-id=640c1f;packetization-mode=1;level-asymmetry-allowed=1"))
        assertTrue(result.contains("a=fmtp:97 apt=96\r\n"))
        assertTrue(result.contains("a=ice-ufrag:keep-ufrag\r\n"))
        assertTrue(result.contains("a=fingerprint:sha-256 keep-fingerprint\r\n"))
    }
    @Test fun applyingProfileTwiceDoesNotDuplicateParametersOrBandwidthLines() {
        val once = StreamProfile.receiveSdp(sdp)
        assertEquals(once, StreamProfile.receiveSdp(once))
    }
    @Test fun noVideoDoesNotModifyAudioOnlySdp() {
        val audio = sdp.substringBefore("m=video")
        assertEquals(audio, StreamProfile.receiveSdp(audio))
    }
    @Test fun tvRequestsSupported4k60ButNeverDowngradesTo4k30OrSwitchesAnExisting4k120Mode() {
        val fullHd = OutputMode(1, 1920, 1080, 60f)
        val slow4k = OutputMode(2, 3840, 2160, 30f)
        val good4k = OutputMode(3, 3840, 2160, 59.94f)
        assertEquals(3, DisplayModePolicy.preferred4k60(fullHd, listOf(fullHd, slow4k, good4k)))
        assertNull(DisplayModePolicy.preferred4k60(fullHd, listOf(fullHd, slow4k)))
        assertNull(DisplayModePolicy.preferred4k60(OutputMode(4, 3840, 2160, 120f), listOf(good4k)))
    }
}
