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
        require(result.host == base.host && result.port == base.port && result.scheme == base.scheme &&
            result.username.isEmpty() && result.password.isEmpty()) { "PLAY: endereço de sessão inesperado" }
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
        }.flatMap { candidate -> teredoFallbacks(candidate) + candidate }
            .distinctBy { Triple(it.sdpMid, it.sdpMLineIndex, it.sdp) }
    }

    /** Xbox may advertise a Teredo endpoint. Preserve IPv6 and add its encoded IPv4 route. */
    private fun teredoFallbacks(candidate: IceCandidate): List<IceCandidate> {
        val address = candidate.sdp.split(Regex("\\s+")).getOrNull(4) ?: return emptyList()
        if (!address.startsWith("2001:", true)) return emptyList()
        val bytes = runCatching { java.net.InetAddress.getByName(address).address }.getOrNull() ?: return emptyList()
        if (bytes.size != 16 || bytes[0] != 0x20.toByte() || bytes[1] != 1.toByte() ||
            bytes[2] != 0.toByte() || bytes[3] != 0.toByte()) return emptyList()
        val ipv4 = (12..15).joinToString(".") { ((bytes[it].toInt() and 255) xor 255).toString() }
        val port = (((bytes[10].toInt() and 255) shl 8) or (bytes[11].toInt() and 255)) xor 65535
        return listOf(9002, port).filter { it > 0 }.distinct().mapIndexed { index, value ->
            IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex,
                "candidate:teredo$index 1 UDP 1 $ipv4 $value typ host")
        }
    }
}
