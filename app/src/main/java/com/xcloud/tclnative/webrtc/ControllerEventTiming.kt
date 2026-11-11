package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import java.util.Locale

/** Constant-memory observations from Android's event timestamp to Activity delivery, not RF latency. */
class ControllerEventTiming {
    private var count=0L
    private var totalMs=0.0
    private var minMs=Long.MAX_VALUE
    private var peakMs=0L
    private var maximumHistory=0
    private var deviceName: String?=null

    @Synchronized fun record(eventUptimeMs: Long,arrivalUptimeMs: Long,historySize: Int=0,name: String?=null) {
        if(eventUptimeMs<0 || arrivalUptimeMs<eventUptimeMs) return
        val age=arrivalUptimeMs-eventUptimeMs
        count++;totalMs+=age.toDouble();minMs=minOf(minMs,age);peakMs=maxOf(peakMs,age)
        maximumHistory=maxOf(maximumHistory,historySize.coerceAtLeast(0))
        name?.take(80)?.let { short ->
            if(short!=deviceName) deviceName=short.map { if(it.isISOControl()) ' ' else it }.joinToString("")
        }
    }
    @Synchronized fun diagnostics(): String {
        if(count==0L) return UiText.text("Evento de joystick Android → app: n/d (sem eventos físicos observados)")
        return UiText.join(UiText.text("Evento de joystick Android → app: média {0} | mín. {1} | pico {2} ms",UiText.nested(String.format(Locale.US,"%.2f",totalMs/count)),UiText.nested(minMs),UiText.nested(peakMs)),UiText.text(" • {0} eventos • histórico máx. {1}",UiText.nested(count),UiText.nested(maximumHistory)),(deviceName?.let { UiText.text(" • controle: {0}",UiText.nested(it)) } ?: ""))
    }
}
