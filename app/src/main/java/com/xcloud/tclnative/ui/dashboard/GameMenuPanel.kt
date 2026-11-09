package com.xcloud.tclnative.ui.dashboard

import android.content.Context

/** The same in-game panel is used by controller and touch shortcuts. */
object GameMenuPanel {
    fun show(context: Context,image: () -> Unit,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,virtualTouch: (() -> Unit)? = null,hud: (() -> Unit)? = null,sessionOptions: (() -> Unit)? = null,audio: (() -> Unit)? = null,
             microphone: (() -> Unit)? = null,microphoneLabel: String="Ativar microfone",mouse: (() -> Unit)? = null,controllerProfiles: (() -> Unit)?=null): ConsoleDialog {
        val actions=mutableListOf(ConsoleAction("Nitidez e saturação",image))
        if(virtualTouch!=null) actions+=ConsoleAction("Usar controle virtual nesta partida",virtualTouch)
        if(hud!=null) actions+=ConsoleAction("HUD e controles deste jogo",hud)
        if(sessionOptions!=null) actions+=ConsoleAction("Bitrate, região e idioma deste jogo",sessionOptions)
        if(controllerProfiles!=null) actions+=ConsoleAction("Calibração e remapeamento do controle",controllerProfiles)
        if(audio!=null) actions+=ConsoleAction("Áudio e microfone",audio)
        if(microphone!=null) actions+=ConsoleAction(microphoneLabel,microphone)
        if(mouse!=null) actions+=ConsoleAction("Capturar / soltar mouse e teclado — F8",mouse)
        actions+=ConsoleAction("Mostrar / ocultar estatísticas",statistics)
        actions+=ConsoleAction("Diagnóstico da partida",diagnostics)
        actions+=ConsoleAction("Desconectar sessão e voltar ao catálogo",disconnect)
        return ConsoleDialogs.menu(context,"Partida • Menu + View",actions)
    }
}
