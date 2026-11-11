package com.xcloud.tclnative

import com.xcloud.tclnative.signaling.QueueEstimate
import com.xcloud.tclnative.signaling.QueueStatus
import org.junit.Assert.*
import org.junit.Test

class QueueStatusTest {
    @Test fun readsTheServicesTotalWaitEstimateAndRoundsUpSeconds() {
        assertEquals(301L, QueueEstimate.parse("""{"estimatedTotalWaitTimeInSeconds":300.2}""")!!.seconds)
    }
    @Test fun missingNegativeOrNonNumericEstimatesDoNotInventAZeroWait() {
        listOf("{}", """{"estimatedTotalWaitTimeInSeconds":null}""",
            """{"estimatedTotalWaitTimeInSeconds":-1}""", """{"estimatedTotalWaitTimeInSeconds":"unknown"}""")
            .forEach { assertNull(QueueEstimate.parse(it)) }
    }
    @Test fun elapsedTimeNeverBecomesAnInventedPersonalizedCountdown() {
        val snapshot = QueueStatus(0, 120_000, QueueEstimate(600), 120_000)
        val text = snapshot.message(125_000)
        assertTrue(text.contains("cerca de 10 min"))
        assertTrue(text.contains("Você já aguardou: 2 min 5s"))
        assertTrue(text.contains("há 5s"))
    }
    @Test fun failedRefreshKeepsTheLastEstimateButMarksItStale() {
        val text = QueueStatus(0, 120_000, QueueEstimate(300), 0, estimateFailed = true).message(125_000)
        assertTrue(text.contains("cerca de 5 min"))
        assertTrue(text.contains("desatualizada"))
        assertTrue(text.contains("consulta da vaga continua"))
    }
    @Test fun unavailableEstimateAndSlowStatePollAreVisibleWhileTimerContinues() {
        val snapshot = QueueStatus(0, 0)
        val text = snapshot.message(25_000)
        assertTrue(text.contains("indisponível"))
        assertTrue(text.contains("Você já aguardou: 25s"))
        assertTrue(text.contains("última confirmação da fila há 25s"))
        assertFalse(text.substringBefore("Você já aguardou").contains("0 min"))
    }
    @Test fun zeroServiceEstimateDoesNotClaimTheSlotIsReady() {
        val text = QueueStatus(0, 0, QueueEstimate(0), 0).message(0)
        assertTrue(text.contains("menos de 1 minuto"))
        assertTrue(text.contains("não é garantia"))
    }
}
