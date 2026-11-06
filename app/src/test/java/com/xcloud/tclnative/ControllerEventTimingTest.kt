package com.xcloud.tclnative

import com.xcloud.tclnative.webrtc.ControllerEventTiming
import org.junit.Assert.*
import org.junit.Test

class ControllerEventTimingTest {
    @Test fun noPhysicalEventsRemainUnknownInsteadOfZeroLatency() {
        val timing=ControllerEventTiming()
        assertTrue(timing.diagnostics().contains("n/d"))
        timing.record(-1,20);timing.record(100,99)
        assertTrue(timing.diagnostics().contains("n/d"))
    }
    @Test fun latestAndroidEventAgeIsAggregatedWithoutReplayingBatchedSamples() {
        val timing=ControllerEventTiming()
        timing.record(100,105,3,"Controle")
        timing.record(110,123,1,"Controle")
        val report=timing.diagnostics()
        assertTrue(report.contains("média 9.00 | mín. 5 | pico 13 ms"))
        assertTrue(report.contains("2 eventos"));assertTrue(report.contains("histórico máx. 3"))
        assertTrue(report.contains("controle: Controle"))
    }
    @Test fun longSessionsKeepAggregatesAndExternalDeviceNamesAreBounded() {
        val timing=ControllerEventTiming()
        repeat(10_000) { timing.record(it*10L,it*10L+2) }
        timing.record(100000,100002,0,"a\n".repeat(200))
        val report=timing.diagnostics()
        assertTrue(report.contains("10001 eventos"));assertTrue(report.contains("média 2.00"))
        assertFalse(report.contains('\n'));assertTrue(report.length<300)
    }
}
