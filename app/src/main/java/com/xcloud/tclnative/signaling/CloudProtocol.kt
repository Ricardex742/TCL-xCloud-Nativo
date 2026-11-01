package com.xcloud.tclnative.signaling

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import java.io.IOException

object CloudProtocol {
    fun sessionUrl(baseUri: String, path: String): HttpUrl {
        val base = (baseUri.trimEnd('/') + "/").toHttpUrl()
        val result = base.resolve(path.trimStart('/')) ?: throw IOException("PLAY: endereço de sessão inválido")
        require(result.host == base.host && result.scheme == base.scheme) { "PLAY: endereço de sessão inesperado" }
        require(result.query == null && result.fragment == null) { "PLAY: endereço de sessão inválido" }
        return result
    }
    fun exchange(body: String): Any? {
        if (body.isBlank()) return null
        val value = JSONObject(body).opt("exchangeResponse")
        return when (value) {
            null, JSONObject.NULL -> null
            is String -> when {
                value.isBlank() -> null
                value.trimStart().startsWith("[") -> JSONArray(value)
                else -> JSONObject(value)
            }
            else -> value
        }
    }
    fun icePayload(candidates: List<IceCandidate>): JSONObject {
        require(candidates.isNotEmpty()) { "ICE_LOCAL: nenhum candidato disponível" }
        val array = JSONArray()
        candidates.distinctBy { Triple(it.sdpMid, it.sdpMLineIndex, it.sdp) }.forEach {
            array.put(JSONObject().put("messageType", "iceCandidate").put("candidate", it.sdp)
                .put("sdpMid", it.sdpMid).put("sdpMLineIndex", it.sdpMLineIndex))
        }
        array.put(JSONObject().put("messageType", "iceCandidate").put("candidate", "a=end-of-candidates")
            .put("sdpMid", candidates.first().sdpMid).put("sdpMLineIndex", candidates.first().sdpMLineIndex))
        return JSONObject().put("messageType", "iceCandidate").put("candidate", array)
    }
    fun remoteCandidates(exchange: Any?): List<IceCandidate> {
        val array = when (exchange) {
            is JSONArray -> exchange
            is JSONObject -> exchange.optJSONArray("candidates")
            else -> null
        } ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val value = array.getJSONObject(i)
            val sdp = value.optString("candidate").trim().removePrefix("a=")
            if (sdp.isBlank() || sdp == "end-of-candidates") null
            else {
                require(sdp.startsWith("candidate:")) { "ICE_REMOTE: candidato inválido" }
                IceCandidate(value.optString("sdpMid", "0"), value.optInt("sdpMLineIndex", 0), sdp)
            }
        }
    }
}
