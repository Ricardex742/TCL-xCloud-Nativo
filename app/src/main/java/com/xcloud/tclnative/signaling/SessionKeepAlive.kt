package com.xcloud.tclnative.signaling

import com.xcloud.tclnative.i18n.UiText

import com.xcloud.tclnative.diagnostics.SessionFailure
import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.CloudRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Retry only the idempotent heartbeat, without replacing the allocation or media connection. */
class SessionKeepAlive(
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    data class Status(val confirmations: Int=0,val failures: Int=0,val recoveries: Int=0,
                      val consecutiveFailures: Int=0,val lastFailure: String?=null)
    @Volatile var status=Status()
        private set

    fun diagnostics(): String {
        val value=status
        return UiText.join(UiText.text("Manutenção da sessão: confirmações {0}; falhas {1}; ",UiText.nested(value.confirmations),UiText.nested(value.failures)),UiText.text("recuperações {0}; falhas consecutivas {1}",UiText.nested(value.recoveries),UiText.nested(value.consecutiveFailures)),(value.lastFailure?.let { UiText.text("; último erro {0}",UiText.nested(it)) } ?: ""))
    }

    suspend fun run(send: suspend () -> Unit) {
        while(true) {
            pause(INTERVAL_MS)
            currentCoroutineContext().ensureActive()
            pulse(send)
        }
    }

    suspend fun pulse(send: suspend () -> Unit) {
        var firstFailureMs: Long?=null
        var retry=0
        while(true) {
            currentCoroutineContext().ensureActive()
            try {
                send()
                currentCoroutineContext().ensureActive()
                val old=status
                status=old.copy(confirmations=old.confirmations+1,
                    recoveries=old.recoveries+if(old.consecutiveFailures>0) 1 else 0,consecutiveFailures=0)
                return
            } catch(cancelled: CancellationException) {
                throw cancelled
            } catch(error: Exception) {
                currentCoroutineContext().ensureActive()
                val old=status
                status=old.copy(failures=old.failures+1,consecutiveFailures=old.consecutiveFailures+1,
                    lastFailure=SessionFailure.from(error).code)
                if(!retryable(error)) throw error
                val start=firstFailureMs ?: clockMs().also { firstFailureMs=it }
                val remaining=RETRY_WINDOW_MS-(clockMs()-start).coerceAtLeast(0)
                if(remaining<=0) throw error
                val serverDelay=(error as? CloudHttpException)?.retryAfterMs ?: 0
                val backoff=RETRY_DELAYS_MS[retry.coerceAtMost(RETRY_DELAYS_MS.lastIndex)]
                retry++
                pause(maxOf(backoff,serverDelay).coerceAtMost(remaining))
                currentCoroutineContext().ensureActive()
                // Do not retry earlier than Retry-After or keep an unconfirmed session indefinitely.
                if(clockMs()-start>=RETRY_WINDOW_MS) throw error
            }
        }
    }

    private fun retryable(error: Exception): Boolean = when(error) {
        is CloudHttpException -> error.stage=="KEEPALIVE" && error.status in setOf(408,429,500,502,503,504)
        is CloudRequestException -> error.stage=="KEEPALIVE" &&
            error.cause !is SSLException && error.cause !is ProtocolException
        else -> false
    }

    companion object {
        const val INTERVAL_MS=30_000L
        const val RETRY_WINDOW_MS=60_000L
        private val RETRY_DELAYS_MS=longArrayOf(1000,2000,4000,8000,15000)
    }
}
