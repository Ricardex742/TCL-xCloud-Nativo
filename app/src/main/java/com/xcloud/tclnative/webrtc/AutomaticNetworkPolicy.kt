package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.settings.IpPreference
import org.webrtc.PeerConnection
import java.util.Locale

/** M137 checks the live pair and backups. The native ICE controller and peer decide migration. */
object AutomaticNetworkPolicy {
    fun configure(config: PeerConnection.RTCConfiguration,preference: IpPreference) {
        config.candidateNetworkPolicy=PeerConnection.CandidateNetworkPolicy.ALL
        config.disableIPv6OnWifi=false
        config.maxIPv6Networks=5
        if(preference!=IpPreference.AUTO) return
        // Lightweight STUN checks, no second media stream, forced removal or ICE restart.
        config.iceBackupCandidatePairPingInterval=1000
        config.stableWritableConnectionPingIntervalMs=1000
        config.iceCheckIntervalStrongConnectivityMs=1000
        config.iceCheckIntervalWeakConnectivityMs=250
        config.iceCheckMinInterval=200
    }
}

/** Observe the actual transport selection, never infer it from candidate order or a fast backup. */
class NetworkRouteMonitor(private val preference: IpPreference) {
    private var pairId: String?=null
    private var changes=0
    private var current="Rota ICE: aguardando estatísticas"
    @Synchronized fun collect(samples: Collection<RtcSample>) {
        val byId=samples.associateBy { it.id }
        val video=samples.filter { it.type=="inbound-rtp" && it.values["active"]!=false &&
            (it.values["kind"] ?: it.values["mediaType"])=="video" }.maxByOrNull { (it.values["bytesReceived"] as? Number)?.toLong() ?: 0 }
        val transport=byId[video?.values?.get("transportId")]
            ?: samples.firstOrNull { it.type=="transport" && it.values["selectedCandidatePairId"] is String }
        val selected=byId[transport?.values?.get("selectedCandidatePairId")]?.takeIf { it.type=="candidate-pair" }
        if(selected==null) { current="Rota ICE: seleção ainda não informada pelo WebRTC";return }
        if(pairId!=null && pairId!=selected.id) changes++
        pairId=selected.id
        val local=byId[selected.values["localCandidateId"]]
        val remote=byId[selected.values["remoteCandidateId"]]
        val rtt=(selected.values["currentRoundTripTime"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it>=0 }
            ?.let { String.format(Locale.US,"%.1f ms",it*1000) } ?: "n/d"
        val alternatives=samples.count { it.type=="candidate-pair" && it.id!=selected.id && it.values["state"]=="succeeded" }
        current="Rota ICE: ${family(local)} → ${family(remote)} | RTT $rtt | mudanças observadas: $changes | pares alternativos validados: $alternatives"
    }
    @Synchronized fun diagnostics(): String = "Rede: ${preference.label}\n"+current+
        if(preference==IpPreference.AUTO) "\nAutomático: verificações contínuas de conectividade/RTT; seleção pelo WebRTC e servidor. Alternativas dependem da rede disponível." else ""
    private fun family(candidate: RtcSample?): String {
        val address=(candidate?.values?.get("address") ?: candidate?.values?.get("ip")) as? String ?: return "n/d"
        return when {
            address.contains(':') -> "IPv6"
            address.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) -> "IPv4"
            else -> "n/d"
        }
    }
}
