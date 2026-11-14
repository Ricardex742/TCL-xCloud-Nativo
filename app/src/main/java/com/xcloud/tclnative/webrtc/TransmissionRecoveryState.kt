package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.net.CloudHttpException
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.IOException

/** A missing replacement frame is a retryable failure, not cancellation of the held Xbox session. */
suspend fun awaitTransmissionFrame(frame: Deferred<Unit>,failure: Deferred<Exception>,timeoutMs: Long=20_000) {
    val presented=withTimeoutOrNull(timeoutMs) {
        select<Unit> { failure.onAwait { throw it };frame.onAwait { } }
        true
    } ?: false
    if(!presented) throw IOException("RECONNECT_FRAME_TIMEOUT: nenhum quadro na transmissão recarregada")
}

/** Old native callbacks cannot terminate a new transport; the cloud allocation has a separate lifetime. */
class TransmissionRecoveryState {
    enum class Phase { ACTIVE, RELOADING, WAITING_RETRY, CLOSED }
    @Volatile var phase=Phase.ACTIVE;private set
    @Volatile var epoch=0;private set
    private var requested=0;private var succeeded=0;private var failed=0;private var repaired=0
    private var lastFailure="n/d"
    fun current(token: Int)=phase!=Phase.CLOSED && epoch==token
    @Synchronized fun begin(): Int? {
        if(phase==Phase.RELOADING || phase==Phase.CLOSED) return null
        phase=Phase.RELOADING;epoch++;requested++;return epoch
    }
    @Synchronized fun connected(token: Int): Boolean {
        if(!current(token) || phase!=Phase.RELOADING) return false
        phase=Phase.ACTIVE;succeeded++;return true
    }
    @Synchronized fun failed(token: Int,error: Throwable?=null) {
        if(current(token) && phase==Phase.RELOADING) { phase=Phase.WAITING_RETRY;failed++;recordFailure(error) }
    }
    @Synchronized fun interrupted(token: Int,error: Throwable?=null): Boolean {
        if(!current(token) || phase!=Phase.ACTIVE) return false
        phase=Phase.WAITING_RETRY;recordFailure(error);return true
    }
    @Synchronized fun repaired() { if(phase==Phase.ACTIVE) repaired++ }
    @Synchronized fun close() { phase=Phase.CLOSED;epoch++ }
    private fun recordFailure(error: Throwable?) {
        if(error==null) return
        lastFailure=if(error is CloudHttpException) "${error.stage}/HTTP_${error.status}" else
            Regex("^[A-Z_]{1,32}:").find(error.message.orEmpty())?.value?.removeSuffix(":") ?: error.javaClass.simpleName.take(80)
    }
    @Synchronized fun diagnostics()=UiText.text("Recuperação manual: imagem {0} | recargas {1} | concluídas {2} | falhas {3}",
        UiText.nested(repaired),UiText.nested(requested),UiText.nested(succeeded),UiText.nested(failed))+"\n"+
        UiText.text("Última falha da recuperação: {0}",UiText.nested(lastFailure))
}
