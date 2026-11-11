package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class NativeTouchProtocolTest {
    @Test fun logicalTouchSlotIsRegisteredOnceAndSurvivesPhysicalGamepadChanges() {
        // The isolated official TAK bridge + source manager emits [{index:0,added:true}].
        val handshake=ChannelHandshake(true)
        listOf("message","input","control").forEach(handshake::onOpen)
        val changes=handshake.onHandshakeAck().filter { !it.binary }.map { JSONObject(String(it.bytes)) }
            .filter { it.optString("message")=="gamepadChanged" }
        assertEquals(1,changes.size)
        assertEquals(0,changes.single().getInt("gamepadIndex")); assertTrue(changes.single().getBoolean("wasAdded"))
        val connections=GamepadConnections()
        // HUD fallback / a physical source using slot zero must reuse the existing logical slot.
        assertFalse(connections.add(0)); assertFalse(connections.remove(0)); assertFalse(connections.add(0))
        assertTrue(connections.add(1)); assertFalse(connections.add(1)); assertTrue(connections.remove(1))
        assertFalse(connections.remove(1)); assertFalse(connections.remove(0))
        assertTrue(handshake.onHandshakeAck().isEmpty())
    }
    @Test fun fractionalContactsMatchTheOfficialReliableV8SerializerFixture() {
        // Official web SDK 10.7.0, generateInputReport(version=8), timestamp normalized to 10ms.
        // See docs/native-touch-official-sdk.md for source hashes and reproduction inputs.
        val expected="04000200000000000000000024400102020002007f0000030000009d000000c702000001" +
            "0000000000000009000000000500000000000002"
        val contacts=listOf(
            NativePointer(3,.1234f,.9876f,1,.5f,.002f,.003f),
            NativePointer(9,1f,0f,2,0f,.0005f,.001f))
        val actual=GamepadPacket.encode(emptyMap(),2,10.0,contacts,1280,720)
        assertEquals(expected,actual.joinToString("") { "%02x".format(it) })
    }

    @Test fun clientPresentationUsesMillimetersAndViewportPixelsInSeparateFields() {
        // A 2400x1080 phone showing a centered 1920x1080 video at 400 DPI.
        val display=ClientDisplayDimensions.fromPixels(2400,1080,1920,1080,400f,400f)
        val content=JSONObject(String(ChannelHandshake.dimensions(display).bytes)).getString("content")
        val json=JSONObject(content)
        assertEquals(121,json.getInt("horizontal")); assertEquals(68,json.getInt("vertical"))
        assertEquals(2400,json.getInt("preferredWidth")); assertEquals(1080,json.getInt("preferredHeight"))
        assertEquals(0,json.getInt("safeAreaLeft")); assertEquals(0,json.getInt("safeAreaTop"))
        assertEquals(2400,json.getInt("safeAreaRight")); assertEquals(1080,json.getInt("safeAreaBottom"))
        assertTrue(json.getBoolean("supportsCustomResolution"))
        val portrait=ClientDisplayDimensions.fromPixels(1080,2400,1080,608,400f,400f)
        assertEquals(68,portrait.horizontalMm); assertEquals(38,portrait.verticalMm)
        assertEquals(1080,portrait.preferredWidth); assertEquals(2400,portrait.preferredHeight)
    }

    @Test fun invalidDeviceDpiFallsBackWithoutSendingPixelCountsAsMillimeters() {
        val display=ClientDisplayDimensions.fromPixels(1920,1080,1920,1080,Float.NaN,0f,320f)
        assertEquals(152,display.horizontalMm); assertEquals(85,display.verticalMm)
        assertEquals(1920,display.preferredWidth)
    }

    @Test fun handshakeOmitsUnknownPresentationInsteadOfInventingDimensions() {
        val handshake=ChannelHandshake(true)
        listOf("input","control","message").forEach(handshake::onOpen)
        val actions=handshake.onHandshakeAck()
        assertFalse(actions.any { String(it.bytes).contains("dimensionschanged") })
        assertTrue(actions.any { it.binary && it.bytes.last()==10.toByte() })
    }

    @Test fun touchWaitsForServerMetadataWhileGamepadsContinueAndUsesItsCoordinateSpace() {
        val received=LinkedBlockingQueue<ByteArray>()
        val pump=RealtimeInputPump({0},{ received.offer(it); true },{ fail("Input failed") },
            initialTouchDimensions=null)
        pump.pointers(listOf(NativePointer(3,.25f,.5f,1),NativePointer(9,.75f,.5f,1)))
        pump.pointers(listOf(NativePointer(3,.25f,.5f,2,0f),NativePointer(9,.75f,.5f,2,0f)))
        try {
            pump.start()
            repeat(3) {
                val gamepad=received.poll(2,TimeUnit.SECONDS)!!
                assertEquals(2,ByteBuffer.wrap(gamepad).order(ByteOrder.LITTLE_ENDIAN).short.toInt())
            }
            assertEquals(0L,pump.metrics.pointerEventsSent)
            // Parse exactly the server's binary metadata format before releasing queued touches.
            val bytes=ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort(16).putInt(720).putInt(1280)
            bytes.flip()
            val metadata=RemoteInputReport.parse(bytes)!!
            pump.setTouchDimensions(TouchInputDimensions(metadata.width!!,metadata.height!!))
            val touches=mutableListOf<ByteArray>()
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2)
            while(touches.size<2 && System.nanoTime()<end) {
                val packet=received.poll(50,TimeUnit.MILLISECONDS) ?: continue
                if(ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 4 != 0) touches.add(packet)
            }
            assertEquals(2,touches.size)
            val down=ByteBuffer.wrap(touches[0]).order(ByteOrder.LITTLE_ENDIAN)
            val up=ByteBuffer.wrap(touches[1]).order(ByteOrder.LITTLE_ENDIAN)
            // 24 gamepad bytes precede the pointer frame. The phone's viewport does not scale input.
            assertEquals(3,down.getInt(47)); assertEquals(320,down.getInt(51)); assertEquals(360,down.getInt(55))
            assertEquals(9,down.getInt(67)); assertEquals(960,down.getInt(71))
            assertEquals(1,down.get(59).toInt()); assertEquals(2,up.get(59).toInt())
            assertEquals(2,up.get(79).toInt())
        } finally { pump.close() }
    }
}
