package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.settings.IpPreference
import org.webrtc.IceCandidate
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.io.IOException

object IceFamilyPolicy {
    const val HEAD_START_MS=750L
    const val IPV6_HEAD_START_MS=HEAD_START_MS
    fun connectionMessage(state: String?,probingIpv6: Boolean): String = connectionMessage(state,if(probingIpv6) 6 else null)
    fun connectionMessage(state: String?,preferredFamily: Int?): String =
        if(preferredFamily!=null && state=="FAILED") "ICE: preferência IPv$preferredFamily sem conexão; tentando IPv${if(preferredFamily==6) 4 else 6}" else "ICE: $state"
    fun isIpv6(candidate: IceCandidate): Boolean = candidate.sdp.split(Regex("\\s+")).getOrNull(4)?.contains(':')==true
    fun ordered(candidates: List<IceCandidate>,preferIpv6: Boolean): List<IceCandidate> =
        if(preferIpv6) candidates.filter(::isIpv6)+candidates.filterNot(::isIpv6) else candidates

    data class Attempt(val preferredFamily: Int?=null,val rejected: Int=0) { val ipv6First get()=preferredFamily==6 }
    suspend fun add(candidates: List<IceCandidate>,preferIpv6: Boolean,add: (IceCandidate)->Boolean,
                    awaitPreferred: suspend () -> Unit): Attempt =
        add(candidates,if(preferIpv6) IpPreference.IPV6 else IpPreference.AUTO,add,awaitPreferred)
    suspend fun add(candidates: List<IceCandidate>,preference: IpPreference,add: (IceCandidate)->Boolean,
                    awaitPreferred: suspend () -> Unit): Attempt {
        val family=preference.preferredFamily
        val preferred=if(family==null) emptyList() else candidates.filter { isIpv6(it)==(family==6) }
        val alternate=if(family==null) candidates else candidates.filter { isIpv6(it)!=(family==6) }
        if(preferred.isEmpty() || alternate.isEmpty()) {
            candidates.forEach { if(!add(it)) throw IOException("ICE_REMOTE: candidato rejeitado pelo WebRTC") }
            return Attempt()
        }
        var accepted=0;var rejected=0
        preferred.forEach { if(add(it)) accepted++ else rejected++ }
        if(accepted>0) {
            try { withTimeoutOrNull(HEAD_START_MS) { awaitPreferred() } }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(_: IOException) { /* An unavailable preferred family still gets the alternate. */ }
        }
        alternate.forEach { if(add(it)) accepted++ else rejected++ }
        if(accepted==0) throw IOException("ICE_REMOTE: nenhum candidato aceito pelo WebRTC")
        return Attempt(family,rejected)
    }
}
