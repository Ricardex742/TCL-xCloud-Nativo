package com.xcloud.tclnative

import com.xcloud.tclnative.diagnostics.*
import com.xcloud.tclnative.net.*
import com.xcloud.tclnative.webrtc.*
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionFailureTest {
    private val privateText="https://private.example/session/secret?token=credential SDP=candidate:192.168.0.1 Bearer private"
    @Test fun httpFailurePreservesStageStatusAndServiceCodeWithoutEndpointBodyOrRequestId() {
        val detail=SessionFailure.from(CloudHttpException("KEEPALIVE",503,"ServiceUnavailable","private-request-id"))
        assertEquals(FailureStage.KEEPALIVE,detail.stage);assertEquals(FailureKind.HTTP,detail.kind)
        assertEquals("KEEPALIVE/HTTP_503",detail.code)
        assertTrue(detail.description.contains("503"));assertTrue(detail.description.contains("ServiceUnavailable"))
        assertFalse(detail.description.contains("private-request-id"));assertFalse(detail.json().toString().contains("private-request-id"))
    }
    @Test fun networkExceptionsKeepTheirOriginAndTypedCauseWithoutCopyingPrivateMessages() {
        for((error,kind) in listOf(SocketTimeoutException(privateText) to FailureKind.TIMEOUT,
            UnknownHostException(privateText) to FailureKind.DNS,SSLHandshakeException(privateText) to FailureKind.TLS)) {
            val detail=SessionFailure.from(CloudRequestException("KEEPALIVE",error))
            assertEquals(FailureStage.KEEPALIVE,detail.stage);assertEquals(kind,detail.kind)
            assertFalse(detail.description.contains("private"));assertFalse(detail.json().toString().contains("credential"))
        }
    }
    @Test fun controlAndIceFailuresAreDifferentFromCodecAndRenderingFailures() {
        for(message in listOf("ICE: FAILED","TRANSPORT: ICE FAILED"))
            assertEquals(FailureKind.ICE_FAILED,SessionFailure.from(IOException(message)).kind)
        assertEquals(FailureKind.ICE_CLOSED,SessionFailure.from(IOException("TRANSPORT: ICE CLOSED")).kind)
        assertEquals(FailureKind.CHANNEL_CLOSED,SessionFailure.from(IOException("CHANNELS: input fechado")).kind)
        assertEquals(FailureKind.CONTROL_SEND,SessionFailure.from(IOException("CHANNELS: falha no envio de input")).kind)
        assertEquals(FailureKind.CODEC,SessionFailure.from(IOException("VIDEO_CODEC: $privateText")).kind)
        val renderer=SessionFailure.from(RenderFailureException(IllegalStateException(privateText)))
        assertEquals(FailureStage.RENDERER,renderer.stage);assertEquals(FailureKind.RENDERER,renderer.kind)
        assertFalse(renderer.description.contains("private"))
    }
    @Test fun unknownErrorsUseOnlyATypeAndStoredFieldsAreBoundedAndValidated() {
        val unknown=SessionFailure.from(IllegalStateException(privateText))
        assertEquals(FailureStage.UNKNOWN,unknown.stage);assertEquals(FailureKind.UNCLASSIFIED,unknown.kind)
        assertTrue(unknown.description.contains("IllegalStateException"));assertFalse(unknown.description.contains("private"))
        assertNull(SessionFailure.read(JSONObject().put("stage","future").put("kind","HTTP")))
        val corrupt=SessionFailure(FailureStage.KEEPALIVE,FailureKind.HTTP,999,privateText,privateText)
        val restored=SessionFailure.read(corrupt.json())!!
        assertNull(restored.httpStatus);assertNull(restored.serviceCode);assertNull(restored.exceptionType)
        assertFalse(restored.description.contains("private"))
        val valid=SessionFailure(FailureStage.KEEPALIVE,FailureKind.HTTP,502,"BadGateway","CloudHttpException")
        assertEquals(valid,SessionFailure.read(valid.json()))
    }
    @Test fun theFirstFailureIsFrozenWithTheReportAndHealthyMetricsDoNotEraseIt() {
        val tracker=SessionReportTracker("Jogo","TV","1080p HQ",60);tracker.begin(0,"Ethernet")
        repeat(7) { i -> tracker.add((i+1)*1000L,StreamMetrics(rttMs=40.0,jitterMs=2.0,packetLossPercent=0.0,
            fps=60.0,decodeMs=8.0,intervalSeconds=1.0),InputMetrics()) }
        tracker.recordFailure(CloudHttpException("KEEPALIVE",503,"ServiceUnavailable",null))
        tracker.recordFailure(IOException("TRANSPORT: ICE CLOSED"))
        val report=tracker.finish(8000,SessionEnd.FAILURE)!!
        assertEquals(FailureStage.KEEPALIVE,report.failure!!.stage);assertTrue(report.score!!>=90)
        assertTrue(report.findings().any { it.contains("interrupção no fim") })
        assertTrue(report.text().contains("KEEPALIVE/HTTP_503"))
        tracker.recordFailure(IOException("CHANNELS: input fechado"))
        assertSame(report,tracker.finish(9000,SessionEnd.USER))
    }
    @Test fun normalCancellationHasNoFailureAndOldReportsExplicitlyLackACause() {
        val tracker=SessionReportTracker("Jogo","TV","1080p HQ",60);tracker.begin(0,"Ethernet")
        tracker.recordFailure(CancellationException("cancelled"))
        val report=tracker.finish(1000,SessionEnd.USER)!!
        assertNull(report.failure);assertFalse(report.text().contains("Motivo do encerramento"))
        assertTrue(report.copy(end=SessionEnd.FAILURE).text().contains("não registrado nesta versão"))
    }
}
