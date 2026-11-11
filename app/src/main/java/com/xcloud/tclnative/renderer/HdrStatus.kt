package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/** Display capability is distinct from the format carried by our negotiated video path. */
object HdrStatus {
    fun displayTypes(context: Context): IntArray? = runCatching {
        val display=(context as? Activity)?.window?.decorView?.display
            ?: (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        display?.hdrCapabilities?.supportedHdrTypes
    }.getOrNull()
    fun displayLabel(types: IntArray?): String = when {
        types==null -> UiText.text("Tela: suporte HDR não informado pelo Android.")
        Display.HdrCapabilities.HDR_TYPE_HDR10 in types -> UiText.text("Tela: HDR10 anunciado pelo Android.")
        else -> UiText.text("Tela: HDR10 não anunciado pelo Android.")
    }
    fun report(context: Context): String = displayLabel(displayTypes(context))+"\n\n"+
        UiText.text("Transmissão deste motor: H.264 de 8 bits, em SDR. A superfície de vídeo usa RGB de 8 bits.\n\n")+
        UiText.text("HDR10 real precisa de um fluxo de 10 bits, cores BT.2020, transferência PQ e reprodução HDR em toda a cadeia. O caminho atual não oferece essa combinação, mesmo em uma TV compatível.\n\n")+
        UiText.text("Aumentar saturação ou forçar o modo HDR da TV não transforma o fluxo SDR em HDR10. Os controles de imagem da própria TV continuam disponíveis.")
}
