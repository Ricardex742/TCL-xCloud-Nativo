package com.xcloud.tclnative

import com.xcloud.tclnative.settings.IpPreference
import com.xcloud.tclnative.webrtc.*
import org.junit.Assert.*
import org.junit.Test
import org.webrtc.PeerConnection

class AutomaticNetworkPolicyTest {
    @Test fun automaticChecksLiveAndBackupRoutesWithoutFilteringFamiliesOrRestartingIce() {
        val config=PeerConnection.RTCConfiguration(emptyList())
        AutomaticNetworkPolicy.configure(config,IpPreference.AUTO)
        assertEquals(PeerConnection.CandidateNetworkPolicy.ALL,config.candidateNetworkPolicy)
        assertFalse(config.disableIPv6OnWifi);assertEquals(5,config.maxIPv6Networks)
        assertEquals(1000,config.iceBackupCandidatePairPingInterval)
        assertEquals(1000,config.stableWritableConnectionPingIntervalMs)
        assertEquals(1000,config.iceCheckIntervalStrongConnectivityMs)
        assertEquals(250,config.iceCheckIntervalWeakConnectivityMs)
        assertEquals(200,config.iceCheckMinInterval)
        assertEquals(PeerConnection.ContinualGatheringPolicy.GATHER_ONCE,config.continualGatheringPolicy)
    }
    private fun sample(id: String,type: String,vararg data: Pair<String,Any>)=RtcSample(id,type,1.0,mapOf(*data))
    private fun stats(selected: String="p4",rtt: Double=.04)=listOf(
        sample("video","inbound-rtp","kind" to "video","transportId" to "t","bytesReceived" to 123L),
        sample("t","transport","selectedCandidatePairId" to selected),
        sample("p4","candidate-pair","localCandidateId" to "l4","remoteCandidateId" to "r4","state" to "succeeded","currentRoundTripTime" to rtt),
        sample("p6","candidate-pair","localCandidateId" to "l6","remoteCandidateId" to "r6","state" to "succeeded","nominated" to true,"currentRoundTripTime" to .001),
        sample("l4","local-candidate","address" to "192.0.2.40"),sample("r4","remote-candidate","address" to "198.51.100.40"),
        sample("l6","local-candidate","address" to "2001:db8::4"),sample("r6","remote-candidate","address" to "2001:db8::6")
    )
    @Test fun reportsActualTransportRouteEvenWhenBackupHasBetterRttAndNeverLogsAddresses() {
        val monitor=NetworkRouteMonitor(IpPreference.AUTO)
        monitor.collect(stats())
        val report=monitor.diagnostics()
        assertTrue(report,report.contains("IPv4 → IPv4 | RTT 40.0 ms"))
        assertTrue(report.contains("pares alternativos validados: 1"));assertTrue(report.contains("mudanças observadas: 0"))
        assertFalse(report.contains("192.0.2"));assertFalse(report.contains("2001:db8"))
        monitor.collect(stats("p6"));assertTrue(monitor.diagnostics().contains("IPv6 → IPv6 | RTT 1.0 ms"))
        assertTrue(monitor.diagnostics().contains("mudanças observadas: 1"))
        monitor.collect(stats("p6"));assertTrue(monitor.diagnostics().contains("mudanças observadas: 1"))
    }
    @Test fun missingSelectionAndInvalidRttDoNotPretendAValidatedRouteOrLatency() {
        val monitor=NetworkRouteMonitor(IpPreference.AUTO)
        monitor.collect(stats().filterNot { it.type=="transport" })
        assertTrue(monitor.diagnostics().contains("seleção ainda não informada"))
        monitor.collect(stats(rtt=Double.NaN));assertTrue(monitor.diagnostics().contains("RTT n/d"))
        monitor.collect(stats(rtt=-1.0));assertTrue(monitor.diagnostics().contains("RTT n/d"))
    }
}
