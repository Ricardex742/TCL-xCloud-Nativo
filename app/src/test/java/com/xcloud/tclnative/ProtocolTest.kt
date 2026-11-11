package com.xcloud.tclnative

import com.xcloud.tclnative.signaling.CloudProtocol
import com.xcloud.tclnative.webrtc.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.IceCandidate
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ProtocolTest {
    @Test fun icePayloadPreservesIpv4Ipv6AndAllMediaSections() {
        val candidates = listOf(
            IceCandidate("0", 0, "candidate:1 1 udp 1 192.168.1.2 5000 typ host"),
            IceCandidate("1", 1, "candidate:2 1 udp 1 2001:db8::1 5001 typ host"))
        val payload = CloudProtocol.icePayload(candidates + candidates.first())
        assertEquals("iceCandidate", payload.getString("messageType")); assertFalse(payload.has("candidates"))
        val entries = payload.getJSONArray("candidate")
        assertEquals(3, entries.length())
        assertEquals(candidates[0].sdp, entries.getJSONObject(0).getString("candidate"))
        assertEquals(candidates[1].sdp, entries.getJSONObject(1).getString("candidate"))
        assertEquals(1, entries.getJSONObject(1).getInt("sdpMLineIndex"))
        assertEquals("a=end-of-candidates", entries.getJSONObject(2).getString("candidate"))
    }
    @Test(expected = IllegalArgumentException::class) fun emptyCandidateListIsAnError() { CloudProtocol.icePayload(emptyList()) }
    @Test fun parsesIceExchangeStringsAndNormalizesOnlyTheSdpAttributePrefix() {
        val response = JSONObject().put("exchangeResponse", JSONArray().put(JSONObject()
            .put("candidate", "a=candidate:2 1 UDP 1 2001:db8::1 5000 typ host").put("sdpMid", "1").put("sdpMLineIndex", 1))
            .put(JSONObject().put("candidate", "a=end-of-candidates")).toString()).toString()
        val result = CloudProtocol.remoteCandidates(CloudProtocol.exchange(response))
        assertEquals(1, result.size); assertTrue(result[0].sdp.startsWith("candidate:"))
        assertTrue(result[0].sdp.contains("2001:db8::1")); assertEquals("1", result[0].sdpMid)
    }
    @Test fun sessionUrlsDoNotDuplicateSlashesOrLoseTheSessionPath() {
        assertEquals("https://region.example/v5/sessions/cloud/id", CloudProtocol.sessionUrl("https://region.example/", "/v5/sessions/cloud/id").toString())
        assertEquals("https://region.example/v5/id", CloudProtocol.sessionUrl("https://region.example", "https://region.example/v5/id").toString())
    }
    @Test(expected = IllegalArgumentException::class) fun sessionCannotRedirectCredentialsToAnotherHost() {
        CloudProtocol.sessionUrl("https://region.example", "https://other.example/session")
    }
    @Test fun handshakeWaitsForAckAndAllChannelsAndConfiguresOnlyOnce() {
        listOf(listOf("message", "control", "input"), listOf("input", "message", "control"), listOf("control", "input", "message")).forEach { order ->
            val protocol = ChannelHandshake().apply {
                displayDimensions=ClientDisplayDimensions.fromPixels(1920,1080,1920,1080,160f,160f)
            }
            val beforeAck = order.flatMap(protocol::onOpen)
            assertFalse(protocol.ready); assertEquals(1, beforeAck.size)
            assertEquals("Handshake", JSONObject(String(beforeAck.single().bytes, Charsets.UTF_8)).getString("type"))
            val actions = protocol.onHandshakeAck()
            assertTrue(protocol.ready)
            assertEquals("authorizationRequest", JSONObject(String(actions.first().bytes, Charsets.UTF_8)).getString("message"))
            val dimensions = actions.filterNot { it.binary }.map { JSONObject(String(it.bytes, Charsets.UTF_8)) }
                .single { it.optString("target").endsWith("dimensionschanged") }
            assertEquals(1920, JSONObject(dimensions.getString("content")).getInt("preferredWidth"))
            assertTrue(actions.filterNot { it.binary }.all { it.bytes.last() != 0.toByte() })
            assertTrue(protocol.onHandshakeAck().isEmpty()); assertTrue(protocol.onOpen("control").isEmpty())
        }
    }
    @Test fun earlyAckDoesNotStartInputBeforeTheInputChannelOpens() {
        val protocol = ChannelHandshake()
        protocol.onOpen("message"); protocol.onOpen("control")
        assertTrue(protocol.onHandshakeAck().isEmpty()); assertFalse(protocol.ready)
        assertTrue(protocol.onOpen("input").isNotEmpty()); assertTrue(protocol.ready)
    }
    @Test fun selectedResolutionIsRequestedOnTheOfficialControlMessageAfterRegistrationOnlyOnce() {
        val aliases=mapOf(StreamResolution.HD_720 to "720",StreamResolution.HD_1080 to "1080",
            StreamResolution.HD_1080_HQ to "1080HQ",StreamResolution.QHD_1440 to "1440")
        for((resolution,alias) in aliases) for(nativeTouch in listOf(false,true)) {
            val protocol=ChannelHandshake(nativeTouch,resolution)
            protocol.onOpen("message");protocol.onOpen("input")
            assertTrue(protocol.onHandshakeAck().isEmpty())
            val actions=protocol.onOpen("control")
            val quality=actions.filterNot { it.binary }.filter {
                JSONObject(String(it.bytes,Charsets.UTF_8)).optString("message")=="userRequestedResolutionUpdate"
            }.single()
            assertEquals("control",quality.channel)
            val body=JSONObject(String(quality.bytes,Charsets.UTF_8))
            assertEquals(2,body.length())
            assertEquals(alias,body.getString("resolutionAlias"))
            assertTrue(actions.indexOf(quality)>actions.indexOfFirst { it.binary })
            assertTrue(protocol.onHandshakeAck().isEmpty());assertTrue(protocol.onOpen("control").isEmpty())
        }
    }
    @Test fun nativeTouchHandshakeRegistersTheLogicalTouchSlotBeforeEnablingPointers() {
        val protocol=ChannelHandshake(true).apply {
            displayDimensions=ClientDisplayDimensions.fromPixels(1280,720,1280,720,320f,320f)
        }
        listOf("input","message","control").forEach(protocol::onOpen)
        val actions=protocol.onHandshakeAck()
        val metadata=actions.single { it.binary }
        assertEquals("input",metadata.channel)
        assertArrayEquals(GamepadPacket.metadata(0.0,10),metadata.bytes)
        assertTrue(actions.indexOf(metadata)<actions.indexOfFirst { !it.binary && String(it.bytes).contains("touchinputenabledchanged") })
        val messages=actions.filterNot { it.binary }.map { JSONObject(String(it.bytes,Charsets.UTF_8)) }
        val connected=messages.single { it.optString("message")=="gamepadChanged" }
        assertEquals(0,connected.getInt("gamepadIndex")); assertTrue(connected.getBoolean("wasAdded"))
        val touch=messages.single { it.optString("target").endsWith("touchinputenabledchanged") }
        assertTrue(JSONObject(touch.getString("content")).getBoolean("touchInputEnabled"))
        val dimensions=JSONObject(messages.single { it.optString("target").endsWith("dimensionschanged") }.getString("content"))
        assertEquals(101,dimensions.getInt("horizontal")); assertEquals(57,dimensions.getInt("vertical"))
        assertEquals(1280,dimensions.getInt("preferredWidth")); assertEquals(720,dimensions.getInt("preferredHeight"))
    }
    @Test fun metadataAndGamepadPacketsMatchTheWireLayout() {
        val metadata = GamepadPacket.metadata(0.0)
        assertEquals("080000000000000000000000000000", metadata.joinToString("") { "%02x".format(it) })
        val bytes = GamepadPacket.encode(GamepadState(buttons = 16, leftX = 1f, leftY = -1f, rightX = -1f, rightY = 1f, rightTrigger = 1f), 1, 10.0)
        assertEquals(38, bytes.size)
        val packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, packet.short.toInt()); assertEquals(1, packet.int); assertEquals(10.0, packet.double, 0.0)
        assertEquals(1, packet.get().toInt()); assertEquals(0, packet.get().toInt()); assertEquals(16, packet.short.toInt())
        assertEquals(32767, packet.short.toInt()); assertEquals(32767, packet.short.toInt())
        assertEquals(-32767, packet.short.toInt()); assertEquals(-32767, packet.short.toInt())
        assertEquals(0, packet.short.toInt()); assertEquals(65535, packet.short.toInt() and 65535)
        assertEquals(1, packet.int); assertEquals(1, packet.order(ByteOrder.BIG_ENDIAN).int)
    }
    @Test fun neutralReportReleasesButtonsAndClampsInvalidAxes() {
        val bytes = GamepadPacket.encode(GamepadState(leftX = Float.NaN, rightX = 5f, leftTrigger = -1f), 2, 0.0)
        val packet = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, packet.getShort(16).toInt()); assertEquals(0, packet.getShort(18).toInt())
        assertEquals(32767, packet.getShort(22).toInt()); assertEquals(0, packet.getShort(26).toInt())
    }

    @Test fun teredoAddsEncodedIpv4RoutesAndKeepsTheOriginalIpv6Candidate() {
        val raw = "candidate:1 1 UDP 1 2001:0:4136:e378:8000:63bf:3fff:fdd2 5000 typ host"
        val candidates = CloudProtocol.remoteCandidates(JSONArray().put(JSONObject()
            .put("candidate", raw).put("sdpMid", "1").put("sdpMLineIndex", 1)))
        assertEquals(3, candidates.size)
        assertTrue(candidates.any { it.sdp.contains("192.0.2.45 9002") })
        assertTrue(candidates.any { it.sdp.contains("192.0.2.45 40000") })
        assertTrue(candidates.any { it.sdp == raw })
        assertTrue(candidates.all { it.sdpMid == "1" && it.sdpMLineIndex == 1 })
    }
    @Test(expected = IllegalArgumentException::class) fun sessionCannotRedirectCredentialsToAnotherPort() {
        CloudProtocol.sessionUrl("https://region.example", "https://region.example:8443/session")
    }
}
