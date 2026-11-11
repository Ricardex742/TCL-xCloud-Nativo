package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import java.io.IOException

/** One PeerConnection's candidates. COMPLETE with no candidates is not a usable result. */
class LocalIceGathering {
    private data class Snapshot(
        val gathering: PeerConnection.IceGatheringState=PeerConnection.IceGatheringState.NEW,
        val candidates: List<IceCandidate> = emptyList(),
        val errorCodes: List<Int> = emptyList(),
        val closed: Boolean=false
    )
    private val updates=MutableStateFlow(Snapshot())
    @Synchronized fun state(state: PeerConnection.IceGatheringState) {
        if(!updates.value.closed) updates.value=updates.value.copy(gathering=state)
    }
    @Synchronized fun candidate(candidate: IceCandidate) {
        val old=updates.value
        if(old.closed || !candidate.sdp.startsWith("candidate:") || candidate.sdpMLineIndex<0) return
        if(old.candidates.none { same(it,candidate) }) updates.value=old.copy(candidates=old.candidates+candidate)
    }
    @Synchronized fun removed(candidates: Array<out IceCandidate>) {
        val old=updates.value
        if(!old.closed) updates.value=old.copy(candidates=old.candidates.filterNot { existing -> candidates.any { same(existing,it) } })
    }
    @Synchronized fun error(code: Int) {
        val old=updates.value
        if(!old.closed && code !in old.errorCodes && old.errorCodes.size<8) updates.value=old.copy(errorCodes=old.errorCodes+code)
    }
    @Synchronized fun close() { updates.value=updates.value.copy(closed=true,candidates=emptyList()) }

    suspend fun awaitCandidates(timeoutMs: Long=8_000): List<IceCandidate> {
        require(timeoutMs>0)
        withTimeoutOrNull(timeoutMs) {
            updates.first { it.closed || (it.gathering==PeerConnection.IceGatheringState.COMPLETE && it.candidates.isNotEmpty()) }
        }
        currentCoroutineContext().ensureActive()
        val snapshot=updates.value
        if(snapshot.closed) throw CancellationException("Coleta ICE local encerrada")
        // Keep usable host/IPv6 candidates even if a STUN request never completed.
        return snapshot.candidates
    }

    /** Counts and numeric errors only: never include SDP, addresses, STUN URLs or errorText. */
    fun diagnostics(attempt: Int): String {
        val snapshot=updates.value
        val types=snapshot.candidates.map { candidate ->
            val fields=candidate.sdp.split(Regex("\\s+"))
            fields.getOrNull(fields.indexOf("typ")+1)
        }
        return UiText.join("ICE local $attempt/2: coleta ${snapshot.gathering.name}; candidatos ${snapshot.candidates.size}"," (host ${types.count { it=="host" }}, srflx ${types.count { it=="srflx" }}, relay ${types.count { it=="relay" }}); ",UiText.text("códigos STUN: {0}",UiText.nested(snapshot.errorCodes.joinToString(",").ifBlank { UiText.text("nenhum informado") })))
    }
    private fun same(a: IceCandidate,b: IceCandidate)=a.sdpMid==b.sdpMid && a.sdpMLineIndex==b.sdpMLineIndex && a.sdp==b.sdp
}

class LocalIceUnavailableException(attempt: Int,diagnostics: String): IOException(
    UiText.join("ICE_LOCAL: nenhum candidato de rede disponível após a coleta $attempt/2. ",UiText.text("A rede pode estar indisponível ou ainda inicializando. Verifique a conexão e tente novamente.\n{0}",UiText.nested(diagnostics))))

/** Retry only local empty gathering, before posting SDP; keep the allocated Xbox session. */
object LocalIceOfferRetry {
    suspend fun prepare(create: suspend (Int)->Pair<String,List<IceCandidate>>,
                        discard: suspend ()->Unit,onRetry: ()->Unit): Pair<String,List<IceCandidate>> {
        return try { create(1) } catch(error: LocalIceUnavailableException) {
            currentCoroutineContext().ensureActive()
            discard()
            currentCoroutineContext().ensureActive()
            onRetry()
            create(2)
        }
    }
}
