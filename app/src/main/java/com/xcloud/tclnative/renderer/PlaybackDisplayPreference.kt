package com.xcloud.tclnative.renderer

import com.xcloud.tclnative.i18n.UiText

import android.view.Window
import android.view.Display
import java.util.Locale

/** Holds a window request only while playing. Android still controls the actual mode. */
class PlaybackDisplayPreference(private val window: Window) {
    private data class Original(val modeId: Int, val refreshRate: Float)
    private var original: Original? = null

    fun request(modeId: Int?) {
        if (modeId == null) { restore(); return }
        val attrs = window.attributes
        if (original == null) original = Original(attrs.preferredDisplayModeId, attrs.preferredRefreshRate)
        window.attributes = attrs.apply {
            preferredDisplayModeId = modeId
            preferredRefreshRate = 0f
        }
    }

    fun restore() {
        val saved = original ?: return
        original = null
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = saved.modeId
            preferredRefreshRate = saved.refreshRate
        }
    }

    /** A successful request is distinct from the active physical mode and the app's rate override. */
    fun diagnostics(display: Display?): String = runCatching {
        if(display==null) return@runCatching UiText.text("Modo de tela: n/d")
        fun rate(hz: Float)=if(hz.isFinite()) String.format(Locale.US,"%.2f",hz) else "n/d"
        fun describe(mode: Display.Mode)="#${mode.modeId} ${mode.physicalWidth}x${mode.physicalHeight}@${rate(mode.refreshRate)}Hz"
        val modes=display.supportedModes.toList()
        val requested=window.attributes.preferredDisplayModeId
        val request=modes.firstOrNull { it.modeId==requested }?.let(::describe)
            ?: if(requested==0) UiText.text("padrão do sistema (#0)") else UiText.text("#{0} não anunciado",UiText.nested(requested))
        UiText.join(UiText.text("Modo de tela pedido: {0} | físico atual: {1} | taxa informada ao app: {2}Hz\n",UiText.nested(request),UiText.nested(describe(display.mode)),UiText.nested(rate(display.refreshRate))),"Modos anunciados: ${modes.take(12).joinToString("; ",transform=::describe)}")
    }.getOrDefault(UiText.text("Modo de tela: não informado pelo Android"))
}
