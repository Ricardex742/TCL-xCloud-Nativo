package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context

/** The same in-game panel is used by controller and touch shortcuts. */
object GameMenuPanel {
    fun show(context: Context,image: () -> Unit,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,virtualTouch: (() -> Unit)? = null,hud: (() -> Unit)? = null,sessionOptions: (() -> Unit)? = null,audio: (() -> Unit)? = null,
             microphone: (() -> Unit)? = null,microphoneLabel: String=UiText.text("Ativar microfone"),mouse: (() -> Unit)? = null,controllerProfiles: (() -> Unit)?=null,
             aspect: (() -> Unit)? = null,language: (() -> Unit)? = null,appearance: (() -> Unit)? = null,
             profile: com.xcloud.tclnative.catalog.XboxProfile? = null): ConsoleDialog {
        val actions=mutableListOf<ConsoleAction>()
        if(SeriesGuidePanel.enabled(context)) actions+=ConsoleAction(UiText.text("Retomar partida")) {}
        if(aspect!=null) actions+=ConsoleAction(UiText.text("Proporção da imagem"),aspect)
        actions+=ConsoleAction(UiText.text("Upscaling e tratamento"),image)
        if(virtualTouch!=null) actions+=ConsoleAction(UiText.text("Usar controle virtual nesta partida"),virtualTouch)
        if(hud!=null) actions+=ConsoleAction(UiText.text("HUD e controles deste jogo"),hud)
        if(sessionOptions!=null) actions+=ConsoleAction(UiText.text("Bitrate, região e idioma deste jogo"),sessionOptions)
        if(controllerProfiles!=null) actions+=ConsoleAction(UiText.text("Calibração e remapeamento do controle"),controllerProfiles)
        if(audio!=null) actions+=ConsoleAction(UiText.text("Áudio e microfone"),audio)
        if(language!=null) actions+=ConsoleAction(UiText.text("Idioma do aplicativo"),language)
        if(appearance!=null && SeriesGuidePanel.enabled(context)) actions+=ConsoleAction(UiText.text("Personalizar Series X|S"),appearance)
        if(microphone!=null) actions+=ConsoleAction(microphoneLabel,microphone)
        if(mouse!=null) actions+=ConsoleAction(UiText.text("Capturar / soltar mouse e teclado — F8"),mouse)
        actions+=ConsoleAction(UiText.text("Mostrar / ocultar estatísticas"),statistics)
        actions+=ConsoleAction(UiText.text("Diagnóstico da partida"),diagnostics)
        actions+=ConsoleAction(UiText.text("Desconectar sessão e voltar ao catálogo"),disconnect)
        return if(SeriesGuidePanel.enabled(context)) SeriesGuidePanel.show(context,UiText.text("Guia da partida"),profile,actions)
            else ConsoleDialogs.menu(context,UiText.text("Partida • Menu + View"),actions)
    }
}
