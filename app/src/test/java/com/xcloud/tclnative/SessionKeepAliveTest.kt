package com.xcloud.tclnative

import com.xcloud.tclnative.net.CloudHttpException
import com.xcloud.tclnative.net.CloudRequestException
import com.xcloud.tclnative.net.retryAfterDelayMs
import com.xcloud.tclnative.signaling.SessionKeepAlive
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.net.ProtocolException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class SessionKeepAliveTest {
    private fun http(status: Int,stage: String="KEEPALIVE",retryAfterMs: Long?=null)=
        CloudHttpException(stage,status,null,null,retryAfterMs)

    @Test fun temporaryHttpFailuresRecoverAndTheNextHeartbeatStartsWithAFreshBudget() = runBlocking {
        var now=0L;val waits=mutableListOf<Long>();var calls=0
        val keeper=SessionKeepAlive({waits.add(it);now+=it},{now})
        keeper.pulse { calls++;if(calls==1) throw http(502);if(calls==2) throw http(503) }
        assertEquals(3,calls);assertEquals(listOf(1000L,2000L),waits)
        assertEquals(1,keeper.status.confirmations);assertEquals(2,keeper.status.failures)
        assertEquals(1,keeper.status.recoveries);assertEquals(0,keeper.status.consecutiveFailures)
        assertTrue(keeper.diagnostics().contains("KEEPALIVE/HTTP_503"))
        var next=0
        keeper.pulse { if(next++==0) throw http(504) }
        assertEquals(1000L,waits.last());assertEquals(2,keeper.status.confirmations)
        assertEquals(2,keeper.status.recoveries)
    }

    @Test fun onlyTemporaryKeepAliveHttpStatusesAreRetried() = runBlocking {
        for(status in listOf(408,429,500,502,503,504)) {
            var now=0L;var calls=0
            val keeper=SessionKeepAlive({now+=it},{now})
            keeper.pulse { if(calls++==0) throw http(status) }
            assertEquals("HTTP $status",2,calls);assertEquals(1,keeper.status.recoveries)
        }
        for(error in listOf(400,401,403,404,410,501).map { http(it) }+http(502,"SDP_OFFER")) {
            var calls=0;val keeper=SessionKeepAlive({fail("Permanent failures must not be retried")},{0})
            val actual=try { keeper.pulse { calls++;throw error };null } catch(e: CloudHttpException) { e }
            assertSame(error,actual);assertEquals(1,calls)
        }
    }

    @Test fun sustainedFailureStopsAfterTheGraceWindowAndPreservesTheActualHttpError() = runBlocking {
        var now=0L;var calls=0;val waits=mutableListOf<Long>();val error=http(502)
        val keeper=SessionKeepAlive({waits.add(it);now+=it},{now})
        val actual=try { keeper.pulse { calls++;throw error };null } catch(e: CloudHttpException) { e }
        assertSame(error,actual);assertEquals(SessionKeepAlive.RETRY_WINDOW_MS,now)
        assertTrue(calls in 2..10);assertTrue(waits.all { it in 1000L..15000L })
        assertEquals(0,keeper.status.confirmations);assertEquals(0,keeper.status.recoveries)
        assertEquals(calls,keeper.status.consecutiveFailures)
    }

    @Test fun socketTimeoutCanRecoverButTlsAndProtocolErrorsAreNotRetried() = runBlocking {
        var now=0L;var calls=0
        val keeper=SessionKeepAlive({now+=it},{now})
        keeper.pulse { if(calls++==0) throw CloudRequestException("KEEPALIVE",SocketTimeoutException()) }
        assertEquals(2,calls);assertTrue(keeper.diagnostics().contains("KEEPALIVE/TIMEOUT"))
        for(cause in listOf(SSLHandshakeException("certificate"),ProtocolException("protocol"))) {
            val error=CloudRequestException("KEEPALIVE",cause);var attempts=0
            val permanent=SessionKeepAlive({fail("TLS/protocol failure must not be retried")},{0})
            val actual=try { permanent.pulse { attempts++;throw error };null } catch(e: CloudRequestException) { e }
            assertSame(error,actual);assertEquals(1,attempts)
        }
    }

    @Test fun cancellationDuringBackoffStopsWithoutSendingAnotherRequest() = runBlocking {
        var sends=0
        val keeper=SessionKeepAlive({throw CancellationException("user left")},{0})
        try { keeper.pulse { sends++;throw http(502) };fail("Expected cancellation") }
        catch(_: CancellationException) {}
        assertEquals(1,sends);assertEquals(1,keeper.status.failures)
        val cancelled=SessionKeepAlive({}, {0})
        try { cancelled.pulse { throw CancellationException("user left") };fail("Expected cancellation") }
        catch(_: CancellationException) {}
        assertEquals(0,cancelled.status.failures)
    }

    @Test fun retryAfterIsRespectedWithoutExtendingTheBoundedFailureWindow() = runBlocking {
        var now=0L;var calls=0;val waits=mutableListOf<Long>()
        val keeper=SessionKeepAlive({waits.add(it);now+=it},{now})
        keeper.pulse { if(calls++==0) throw http(429,retryAfterMs=5000) }
        assertEquals(listOf(5000L),waits)
        calls=0;now=0
        val longDelay=http(503,retryAfterMs=120_000)
        val bounded=SessionKeepAlive({now+=it},{now})
        val actual=try { bounded.pulse { calls++;throw longDelay };null } catch(e: CloudHttpException) { e }
        assertSame(longDelay,actual);assertEquals(1,calls);assertEquals(60_000L,now)
    }

    @Test fun retryAfterSupportsSecondsHttpDatesAndInvalidHeaders() {
        assertEquals(5000L,retryAfterDelayMs("5",0))
        assertEquals(60_000L,retryAfterDelayMs("Thu, 01 Jan 1970 00:01:00 GMT",0))
        assertEquals(0L,retryAfterDelayMs("Thu, 01 Jan 1970 00:01:00 GMT",120_000))
        assertNull(retryAfterDelayMs("invalid",0));assertNull(retryAfterDelayMs("-1",0))
        assertNull(retryAfterDelayMs(null,0))
    }

    @Test fun maintenanceBackoffLeavesSiblingMonitoringActiveAndCancelsWithTheSession() = runBlocking {
        withTimeout(3000) {
            var now=0L;var calls=0;var ticks=0
            val retrying=CompletableDeferred<Unit>();val resume=CompletableDeferred<Unit>();val confirmed=CompletableDeferred<Unit>()
            val keeper=SessionKeepAlive({ wait ->
                now+=wait
                if(wait==1000L) { retrying.complete(Unit);resume.await() }
                if(wait==SessionKeepAlive.INTERVAL_MS && calls>=2) { confirmed.complete(Unit);awaitCancellation() }
            },{now})
            val job=launch { keeper.run { if(calls++==0) throw http(502) } }
            try {
                retrying.await()
                launch { ticks++ }.join()
                assertTrue(job.isActive);assertEquals(1,ticks)
                resume.complete(Unit);confirmed.await()
                assertEquals(1,keeper.status.recoveries);assertEquals(1,keeper.status.confirmations)
            } finally { job.cancelAndJoin() }
            assertEquals(2,calls)
        }
    }

    @Test fun permanentMaintenanceFailurePropagatesAndStopsSiblingMonitoring() = runBlocking {
        val error=http(401);var monitorStopped=false
        val actual=try {
            coroutineScope {
                launch(start=CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { monitorStopped=true } }
                launch(Dispatchers.IO) { SessionKeepAlive({}, {0}).run { throw error } }
            }
            null
        } catch(e: CloudHttpException) { e }
        assertSame(error,actual);assertTrue(monitorStopped)
    }
}
