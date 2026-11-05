package com.xcloud.tclnative.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.io.IOException

object NetworkSnapshot {
    fun read(context: Context): String = runCatching {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps=manager?.getNetworkCapabilities(manager.activeNetwork) ?: return@runCatching "Rede não informada"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Rede móvel"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wifi=runCatching { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo }.getOrNull()
                val band=when(wifi?.frequency ?: 0) { in 2400..2500 -> " • 2,4 GHz"; in 4900..5900 -> " • 5 GHz"; in 5925..7125 -> " • 6 GHz"; else -> "" }
                val speed=wifi?.linkSpeed?.takeIf { it>0 }?.let { " • enlace $it Mbps" } ?: ""
                "Wi-Fi$band$speed"
            }
            else -> "Outra rede"
        }
    }.getOrDefault("Rede não informada")
}

/** Only the latest aggregate is retained; no IP/SSID/token/SDP or unbounded event history. */
class SessionReportStore(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("session_diagnostic",Context.MODE_PRIVATE)
    fun save(r: SessionReport) {
        val data=JSONObject().put("schema",1).put("game",r.game).put("device",r.device).put("network",r.network)
            .put("profile",r.requestedProfile).put("presentationFps",r.presentationFps).put("duration",r.durationMs)
            .put("observed",r.observedMs).put("samples",r.samples).put("endedAt",r.endedAtMs).put("end",r.end.name)
            .put("resolution",r.resolution).put("codec",r.codec).put("decoder",r.decoder).put("efficient",r.efficient)
            .put("videoBytes",r.videoBytes).put("receivedBytes",r.receivedBytes).put("uploadedBytes",r.uploadedBytes)
            .put("inputBytes",r.inputBytes).put("inputDetails",r.inputDetails?.take(1024)).put("dropped",r.droppedFrames).put("pointers",r.pointerEvents)
            .put("waits",r.inputWaits).put("coalesced",r.inputCoalesced).put("stallMs",r.stalledMs)
            .put("stalls",r.stalledEvents).put("noData",r.stalledWithoutDataMs).put("withData",r.stalledWithDataMs).put("score",r.score)
        val metrics=JSONObject()
        r.metrics.forEach { (key,m) -> metrics.put(key.name,JSONObject().put("avg",m.average).put("min",m.minimum).put("peak",m.peak).put("samples",m.samples)) }
        data.put("metrics",metrics)
        data.put("recovery",JSONObject().apply { r.recovery.forEach { (k,v) -> put(k.name,v) } })
        if(!prefs.edit().putString("last",data.toString()).commit()) throw IOException("Não foi possível salvar o diagnóstico neste aparelho.")
    }
    fun read(): SessionReport? { return try {
        val text=prefs.getString("last",null)
        if(text==null || text.length>65536) null else {
            val d=JSONObject(text)
            if(d.optInt("schema")!=1) null else {
                fun s(k: String,n: Int=180)=d.optString(k).takeIf { it.isNotBlank() && it!="null" }?.take(n)
                fun l(k: String): Long?=if(d.has(k) && !d.isNull(k)) d.optLong(k).takeIf { it>=0 } else null
                fun n(k: String)=l(k) ?: 0
                val metrics=ReportMetric.entries.mapNotNull { key ->
                    val m=d.optJSONObject("metrics")?.optJSONObject(key.name) ?: return@mapNotNull null
                    val avg=m.optDouble("avg",Double.NaN); val low=m.optDouble("min",Double.NaN); val high=m.optDouble("peak",Double.NaN)
                    if(!avg.isFinite() || !low.isFinite() || !high.isFinite() || low<0 || avg<0 || high<low) null
                    else key to MetricSummary(avg,low,high,m.optLong("samples").coerceAtLeast(0))
                }.toMap()
                val recovery=VideoRecoveryValues.read(d.optJSONObject("recovery"))
                SessionReport(s("game",256) ?: "Jogo",s("device") ?: "Dispositivo não informado",s("network") ?: "Rede não informada",
                    s("profile") ?: "Perfil não informado",d.optInt("presentationFps",60).coerceIn(1,250),n("duration"),n("observed"),n("samples"),n("endedAt"),
                    SessionEnd.entries.firstOrNull { it.name==d.optString("end") } ?: return null,metrics,s("resolution",48),s("codec",80),s("decoder",160),
                    if(d.has("efficient")) d.optBoolean("efficient") else null,l("videoBytes"),l("receivedBytes"),l("uploadedBytes"),n("inputBytes"),
                    l("dropped"),n("pointers"),n("waits"),n("coalesced"),n("stallMs"),n("stalls"),n("noData"),n("withData"),recovery,
                    if(d.has("score")) d.optInt("score").takeIf { it in 0..100 } else null,s("inputDetails",1024))
            }
        }
    } catch(_: Exception) { null } }
    private object VideoRecoveryValues {
        fun read(json: JSONObject?)=com.xcloud.tclnative.webrtc.VideoRecoveryAction.entries.mapNotNull { key ->
            json?.optInt(key.name)?.takeIf { it>0 }?.let { key to it }
        }.toMap()
    }
}
