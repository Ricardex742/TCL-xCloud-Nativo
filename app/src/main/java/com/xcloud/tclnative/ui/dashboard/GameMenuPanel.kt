package com.xcloud.tclnative.ui.dashboard

import android.content.Context

/** The same in-game panel is used by controller and touch shortcuts. */
object GameMenuPanel {
    fun show(context: Context,image: () -> Unit,statistics: () -> Unit,diagnostics: () -> Unit,
             disconnect: () -> Unit,virtualTouch: (() -> Unit)? = null): ConsoleDialog {
        val actions=mutableListOf(ConsoleAction("Nitidez e saturação",image))
        if(virtualTouch!=null) actions+=ConsoleAction("Usar controle virtual nesta partida",virtualTouch)
        actions+=ConsoleAction("Mostrar / ocultar estatísticas",statistics)
        actions+=ConsoleAction("Diagnóstico da partida",diagnostics)
        actions+=ConsoleAction("Desconectar sessão e voltar ao catálogo",disconnect)
        return ConsoleDialogs.menu(context,"Partida • Menu + View",actions)
    }
}
