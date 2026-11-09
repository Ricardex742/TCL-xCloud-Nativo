package com.xcloud.tclnative.webrtc

import org.webrtc.IceCandidate
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.io.IOException

object IceFamilyPolicy {
    const val IPV6_HEAD_START_MS=750L
    fun connectionMessage(state: String?,probingIpv6: Boolean): String =
        if(probingIpv6 && state=="FAILED") "ICE: preferência IPv6 sem conexão; tentando IPv4" else "ICE: $state"
    fun isIpv6(candidate: IceCandidate): Boolean = candidate.sdp.split(Regex("\\s+")).getOrNull(4)?.contains(':')==true
    fun ordered(candidates: List<IceCandidate>,preferIpv6: Boolean): List<IceCandidate> =
        if(preferIpv6) candidates.filter(::isIpv6)+candidates.filterNot(::isIpv6) else candidates

    data class Attempt(val ipv6First: Boolean,val rejected: Int=0)
    suspend fun add(candidates: List<IceCandidate>,preferIpv6: Boolean,add: (IceCandidate)->Boolean,
                    awaitPreferred: suspend () -> Unit): Attempt {
        val six=candidates.filter(::isIpv6);val four=candidates.filterNot(::isIpv6)
        if(!preferIpv6 || six.isEmpty() || four.isEmpty()) {
            candidates.forEach { if(!add(it)) throw IOException("ICE_REMOTE: candidato rejeitado pelo WebRTC") }
            return Attempt(false)
        }
        var accepted=0;var rejected=0
        six.forEach { if(add(it)) accepted++ else rejected++ }
        if(accepted>0) {
            try { withTimeoutOrNull(IPV6_HEAD_START_MS) { awaitPreferred() } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: IOException) { /* An unavailable preferred family still gets IPv4. */ }
        }
        four.forEach { if(add(it)) accepted++ else rejected++ }
        if(accepted==0) throw IOException("ICE_REMOTE: nenhum candidato aceito pelo WebRTC")
        return Attempt(true,rejected)
    }
}
