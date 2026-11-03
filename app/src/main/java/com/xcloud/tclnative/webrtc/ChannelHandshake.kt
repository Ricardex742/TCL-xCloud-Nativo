package com.xcloud.tclnative.webrtc

import org.json.JSONObject
import java.util.UUID

data class ChannelMessage(val channel: String, val bytes: ByteArray, val binary: Boolean = false)

/** Ordered protocol transitions; callbacks for the three channels can arrive in any order. */
class ChannelHandshake(private val nativeTouch: Boolean = false, private val width: Int = 1920, private val height: Int = 1080) {
    private val opened = mutableSetOf<String>()
    private var acknowledged = false
    private var started = false
    val ready: Boolean @Synchronized get() = started

    @Synchronized fun onOpen(channel: String): List<ChannelMessage> {
        if (!opened.add(channel)) return emptyList()
        val messages = mutableListOf<ChannelMessage>()
        if (channel == "message") messages += encode("message", JSONObject()
            .put("type", "Handshake").put("version", "messageV1").put("id", UUID.randomUUID().toString()).put("cv", "0"))
        messages += startIfReady()
        return messages
    }
    @Synchronized fun onHandshakeAck(): List<ChannelMessage> {
        acknowledged = true
        return startIfReady()
    }
    private fun startIfReady(): List<ChannelMessage> {
        if (started || !acknowledged || !opened.containsAll(listOf("message", "control", "input"))) return emptyList()
        started = true
        return listOf(encode("control", JSONObject().put("message", "authorizationRequest").put("accessKey", "4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"))) +
            (if(nativeTouch) emptyList() else listOf(encode("control", JSONObject().put("message", "gamepadChanged").put("gamepadIndex", 0).put("wasAdded", true)))) + listOf(
            // The server must learn maxTouchPoints before touchInputEnabled, as in the web client.
            ChannelMessage("input", GamepadPacket.metadata(0.0, if(nativeTouch) 10 else 0), true),
            config("/streaming/systemUi/configuration", JSONObject().put("version", org.json.JSONArray(listOf(0, 2, 0))).put("systemUis", org.json.JSONArray())),
            config("/streaming/properties/clientappinstallidchanged", JSONObject().put("clientAppInstallId", UUID.randomUUID().toString())),
            config("/streaming/characteristics/orientationchanged", JSONObject().put("orientation", 0)),
            config("/streaming/characteristics/touchinputenabledchanged", JSONObject().put("touchInputEnabled", nativeTouch)),
            config("/streaming/characteristics/clientdevicecapabilities", JSONObject()),
            config("/streaming/characteristics/dimensionschanged", JSONObject()
                .put("horizontal", width).put("vertical", height).put("preferredWidth", width).put("preferredHeight", height)
                .put("safeAreaLeft", 0).put("safeAreaTop", 0).put("safeAreaRight", width).put("safeAreaBottom", height)
                .put("supportsCustomResolution", true))
        )
    }
    private fun config(target: String, content: JSONObject) = encode("message", JSONObject()
        .put("type", "Message").put("target", target).put("id", UUID.randomUUID().toString()).put("cv", "")
        .put("content", content.toString()))

    companion object {
        fun encode(channel: String, json: JSONObject) = ChannelMessage(channel, json.toString().toByteArray(Charsets.UTF_8))
    }
}
