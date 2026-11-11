package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Slow gain increase and fast reduction based on rendered PCM peaks; never buffers audio. */
class AudioPeakGuard {
    @Volatile var requested=100
        private set
    @Volatile var effective=1.0
        private set
    private var peak=0.0
    private var lastUpdate=0L
    @Volatile var measured=true
        private set
    @Synchronized fun set(percent: Int) {
        requested=percent.coerceIn(0,300)
        effective=if(requested<=100) requested/100.0 else effective.coerceIn(1.0,requested/100.0)
        peak=0.0
    }
    @Synchronized fun observe(bytes: ByteArray,format: Int,nowMs: Long): Boolean {
        if(requested<=100) return false
        if(format!=2) { measured=false;val changed=effective!=1.0;effective=1.0;return changed }
        measured=true
        var index=0
        while(index+1<bytes.size) {
            val sample=((bytes[index].toInt() and 255) or (bytes[index+1].toInt() shl 8)).toShort().toInt()
            peak=max(peak,abs(sample)/32768.0);index+=2
        }
        if(nowMs-lastUpdate<100) return false
        lastUpdate=nowMs
        val previous=effective
        effective=if(peak>.999) min(1.0,effective*.5) else if(peak>.94) max(.1,effective*.92/peak)
            else min(requested/100.0,effective+.04)
        peak=0.0
        return abs(previous-effective)>.001
    }
    fun diagnostics()=UiText.text("Ganho solicitado: {0}% • aplicado: {1}% • redução adaptativa de picos: {2}",UiText.nested(requested),UiText.nested((effective*100).toInt()),UiText.nested(if(measured) UiText.text("disponível") else UiText.text("formato indisponível; limitado a 100%")))
}
