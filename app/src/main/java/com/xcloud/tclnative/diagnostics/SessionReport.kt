package com.xcloud.tclnative.diagnostics

import com.xcloud.tclnative.webrtc.*
import java.util.Locale
import kotlin.math.roundToInt

enum class ReportMetric(val label: String,val unit: String) {
    RTT("Latência RTT","ms"),LOSS("Perda de pacotes","%"),JITTER("Jitter da rede","ms"),
    FPS("FPS decodificados","FPS"),BITRATE("Bitrate de vídeo","Mbps"),DECODE("Decodificação","ms/quadro"),
    BUFFER("Buffer de vídeo","ms"),INPUT_DELAY("Atraso local do input","ms")
}
data class MetricSummary(val average: Double,val minimum: Double,val peak: Double,val samples: Long)
enum class SessionEnd(val label: String) { USER("Encerrada pelo usuário"),FAILURE("Falha na transmissão"),REPLACED("Outra partida iniciada"),APP_CLOSED("Aplicativo fechado") }
data class SessionReport(
    val game: String,val device: String,val network: String,val requestedProfile: String,val presentationFps: Int,
    val durationMs: Long,val observedMs: Long,val samples: Long,val endedAtMs: Long,val end: SessionEnd,
    val metrics: Map<ReportMetric,MetricSummary>,val resolution: String?,val codec: String?,val decoder: String?,
    val efficient: Boolean?,val videoBytes: Long?,val receivedBytes: Long?,val uploadedBytes: Long?,val inputBytes: Long,
    val droppedFrames: Long?,val pointerEvents: Long,val inputWaits: Long,val inputCoalesced: Long,
    val stalledMs: Long,val stalledEvents: Long,val stalledWithoutDataMs: Long,val stalledWithDataMs: Long,
    val recovery: Map<VideoRecoveryAction,Int>,val score: Int?
) {
    val quality: String get()=when { score==null -> "Dados parciais"; score>=90 -> "Excelente"; score>=75 -> "Boa"; score>=60 -> "Regular"; else -> "Atenção" }
    fun findings(): List<String> = buildList {
        fun avg(k: ReportMetric)=metrics[k]?.average
        if((avg(ReportMetric.LOSS) ?: 0.0)>2 || (avg(ReportMetric.JITTER) ?: 0.0)>15)
            add("Perdas ou jitter elevados. Compare Ethernet e Wi-Fi próximo ao roteador; o serviço também pode variar.")
        if((avg(ReportMetric.RTT) ?: 0.0)>85) add("RTT elevado. Compare a conexão e a região do serviço. RTT não mede a latência completa do comando até a imagem.")
        if((avg(ReportMetric.DECODE) ?: 0.0)>16.7) add("Decodificação acima de 16,7 ms/quadro. Compare 720p e o modo de compatibilidade; desative filtros para testar.")
        if(stalledWithDataMs>0) add("Houve vídeo recebido sem progresso da imagem. Compare decoder, resolução, filtros e perdas de pacotes.")
        if(stalledWithoutDataMs>0) add("Houve pausas sem recebimento de vídeo. Investigue a rede e a continuidade do envio pelo serviço.")
        if(inputWaits>0) add("O canal de input apresentou espera por congestionamento. Compare a estabilidade da conexão.")
        if(score==null) add("Amostras insuficientes para calcular o índice completo. Campos não medidos aparecem como n/d.")
        if(isEmpty()) add("Os indicadores disponíveis não apresentaram um alerta importante nesta coleta.")
    }
    fun text(): String = buildString {
        append("xCLOUD • Diagnóstico pós-partida\n$game\n$device\n$network\n")
        append("Tempo conectado: ${duration(durationMs)} | Observado: ${duration(observedMs)} | $samples amostras\n")
        append("${end.label} | Pedido: $requestedProfile | Apresentação solicitada: $presentationFps FPS\n")
        append("Recebido: ${resolution ?: "n/d"} | ${codec ?: "n/d"} | ${decoder ?: "n/d"}\n")
        append("Índice estimado: ${score?.toString() ?: "n/d"}/100 • $quality\n\n")
        ReportMetric.entries.forEach { key ->
            val m=metrics[key]
            append("${key.label}: média ${number(m?.average)} | mín. ${number(m?.minimum)} | pico ${number(m?.peak)} ${key.unit}\n")
        }
        append("\nRecebido WebRTC: ${bytes(receivedBytes)} | Enviado WebRTC: ${bytes(uploadedBytes)}\n")
        append("Vídeo RTP: ${bytes(videoBytes)} | Input aceito pelo canal: ${bytes(inputBytes)}\n")
        append("Quadros descartados: ${droppedFrames ?: "n/d"} | Toques enviados: $pointerEvents\n")
        append("Sem progresso de imagem: ${duration(stalledMs)} em $stalledEvents episódio(s) observados\n")
        append("Sem dados: ${duration(stalledWithoutDataMs)} | Com dados: ${duration(stalledWithDataMs)}\n")
        append("Input: $inputWaits esperas | $inputCoalesced transições consolidadas\n")
        append("Recuperações tentadas: ${recovery.entries.joinToString { "${it.key}: ${it.value}" }.ifBlank { "nenhuma" }}\n\n")
        append(findings().joinToString("\n"))
        append("\n\nValores das amostras disponíveis. FPS do decoder não é FPS do jogo nem da tela. Contadores WebRTC podem não incluir todo o tráfego da conexão. O índice é uma estimativa local, não um teste de velocidade. Pausas são observadas pelo watchdog, não por captura de cada quadro.")
    }
    companion object {
        fun number(n: Double?)=n?.takeIf { it.isFinite() }?.let { String.format(Locale.US,"%.2f",it) } ?: "n/d"
        fun duration(ms: Long): String { val s=ms.coerceAtLeast(0)/1000; return "%d:%02d:%02d".format(Locale.US,s/3600,(s/60)%60,s%60) }
        fun bytes(value: Long?): String {
            if(value==null) return "n/d"
            val v=value.coerceAtLeast(0).toDouble()
            return when { v>=1073741824 -> "%.2f GB".format(Locale.US,v/1073741824); v>=1048576 -> "%.2f MB".format(Locale.US,v/1048576); v>=1024 -> "%.1f KB".format(Locale.US,v/1024); else -> "${value.coerceAtLeast(0)} B" }
        }
    }
}

/** Constant memory per session: aggregates, counters and watchdog observations, not per-second lists. */
class SessionReportTracker(private val game: String,private val device: String,private val profile: String,
                           private val fpsTarget: Int,private val wallClock: () -> Long = System::currentTimeMillis) {
    private class Aggregate {
        var sum=0.0; var weight=0.0; var low=Double.POSITIVE_INFINITY; var high=0.0; var count=0L
        fun add(v: Double?,w: Double) { if(v==null || !v.isFinite() || v<0 || !w.isFinite() || w<=0) return
            sum+=v*w; weight+=w; low=minOf(low,v); high=maxOf(high,v); count++ }
        fun snapshot()=if(count==0L) null else MetricSummary(sum/weight,low,high,count)
    }
    private class Counter {
        var total=0L; var last=0L; var source: String?=null; var known=false
        fun add(key: String?,v: Long?) { if(v==null || v<0) return
            total+=if(!known || key!=source || v<last) v else v-last
            source=key; last=v; known=true }
        fun value()=if(known) total else null
    }
    private val values=ReportMetric.entries.associateWith { Aggregate() }
    private val video=Counter(); private val down=Counter(); private val up=Counter()
    private var start: Long?=null; private var frozen=false; private var delivered=false; private var report: SessionReport?=null
    private var visible=true; private var network="Rede não informada"; private var resumed=false
    private var samples=0L; private var observed=0.0; private var lastSample=Long.MIN_VALUE
    private var resolution: String?=null; private var codec: String?=null; private var decoder: String?=null; private var efficient: Boolean?=null
    private var dropped=0L; private var droppedKnown=false; private var input=InputMetrics()
    private var lost=0L; private var received=0L; private var lossCounters=false
    private var probeAt: Long?=null; private var token: Long?=null; private var stalled=false
    private var stallMs=0L; private var stallEvents=0L; private var noDataMs=0L; private var withDataMs=0L
    private val recovery=mutableMapOf<VideoRecoveryAction,Int>()
    @Synchronized fun begin(now: Long,network: String) { if(start==null && !frozen) { start=now; this.network=network.take(180) } }
    @Synchronized fun setVisible(value: Boolean) {
        if(value && !visible) resumed=true
        visible=value
        if(!value) { probeAt=null; token=null; stalled=false }
    }
    @Synchronized fun add(now: Long,m: StreamMetrics,i: InputMetrics) {
        if(start==null || frozen || now<=lastSample) return
        val previousTime=lastSample
        lastSample=now; input=i
        video.add(m.videoStreamId,m.totalReceivedVideoBytes)
        down.add(m.transportId,m.transportReceivedBytes); up.add(m.transportId,m.transportSentBytes)
        if(m.width!=null && m.height!=null) resolution="${m.width}x${m.height}"
        m.codec?.let { codec=it.take(80) }; m.decoder?.let { decoder=it.take(160) }; m.powerEfficient?.let { efficient=it }
        if(!visible) return
        // A delta spanning background time is not a foreground quality observation.
        if(resumed) { resumed=false; return }
        val elapsed=(now-maxOf(start!!,previousTime)).toDouble()/1000
        val interval=m.intervalSeconds ?: elapsed
        val seconds=minOf(interval,elapsed).takeIf { it.isFinite() && it>0 && it<=10 } ?: return
        samples++
        observed+=seconds
        fun add(k: ReportMetric,v: Double?,w: Double=seconds)=values.getValue(k).add(v,w)
        add(ReportMetric.RTT,m.rttMs); add(ReportMetric.LOSS,m.packetLossPercent); add(ReportMetric.JITTER,m.jitterMs)
        add(ReportMetric.FPS,m.fps); add(ReportMetric.BITRATE,m.bitrateMbps); add(ReportMetric.BUFFER,m.jitterBufferMs)
        add(ReportMetric.DECODE,m.decodeMs,m.decodedFrames?.toDouble() ?: seconds)
        add(ReportMetric.INPUT_DELAY,i.maxSchedulerDelayMs)
        m.droppedFrames?.takeIf { it>=0 }?.let { dropped+=it; droppedKnown=true }
        if(m.receivedPackets!=null && m.lostPackets!=null) { received+=m.receivedPackets.coerceAtLeast(0); lost+=m.lostPackets.coerceAtLeast(0); lossCounters=true }
    }
    @Synchronized fun observe(now: Long,presentedToken: Long,receiving: Boolean?,action: VideoRecoveryAction) {
        if(start==null || frozen || !visible) return
        if(action!=VideoRecoveryAction.NONE) recovery[action]=(recovery[action] ?: 0)+1
        val previous=probeAt; val old=token
        probeAt=now; token=presentedToken
        if(previous==null || old==null) return
        val dt=(now-previous).coerceIn(0,2500)
        if(presentedToken==old && dt>0) {
            if(!stalled) { stallEvents++; stalled=true }
            stallMs+=dt
            when(receiving) { true -> withDataMs+=dt; false -> noDataMs+=dt; null -> Unit }
        } else stalled=false
    }
    @Synchronized fun finish(now: Long,end: SessionEnd,finalInput: InputMetrics?=null): SessionReport? {
        if(frozen) return report
        frozen=true
        val began=start ?: return null
        finalInput?.let { input=it }
        val metrics=values.mapNotNull { (k,v) -> v.snapshot()?.let { k to it } }.toMap().toMutableMap()
        if(lossCounters && received+lost>0) metrics[ReportMetric.LOSS]?.let { metrics[ReportMetric.LOSS]=it.copy(average=100.0*lost/(received+lost)) }
        val complete=listOf(ReportMetric.RTT,ReportMetric.LOSS,ReportMetric.JITTER,ReportMetric.FPS,ReportMetric.DECODE).all { (metrics[it]?.samples ?: 0)>=3 } && observed>=5
        val score=if(!complete) null else {
            fun avg(k: ReportMetric)=metrics.getValue(k).average
            (100-minOf(30.0,(avg(ReportMetric.RTT)-30).coerceAtLeast(0.0)*.4)-minOf(30.0,avg(ReportMetric.LOSS)*15)-
                minOf(15.0,(avg(ReportMetric.JITTER)-2).coerceAtLeast(0.0)*2)-
                minOf(15.0,(fpsTarget-avg(ReportMetric.FPS)-2).coerceAtLeast(0.0)*2.5)-
                minOf(10.0,(avg(ReportMetric.DECODE)-12).coerceAtLeast(0.0)*1.5)-
                minOf(40.0,100.0*stallMs/(observed*1000))).roundToInt().coerceIn(0,100)
        }
        report=SessionReport(game.take(256),device.take(180),network,profile.take(180),fpsTarget,(now-began).coerceAtLeast(0),
            (observed*1000).toLong(),samples,wallClock(),end,metrics,resolution,codec,decoder,efficient,video.value(),down.value(),up.value(),
            input.sentBytes,dropped.takeIf { droppedKnown },input.pointerEventsSent,input.backpressureSkips,input.coalescedEdges,
            stallMs,stallEvents,noDataMs,withDataMs,recovery.toMap(),score)
        return report
    }
    @Synchronized fun takeReport(): SessionReport? { val ready=report ?: return null; if(delivered) return null; delivered=true; return ready }
}
