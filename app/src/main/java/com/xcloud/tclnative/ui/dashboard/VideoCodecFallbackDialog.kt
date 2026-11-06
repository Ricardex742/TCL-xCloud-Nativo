package com.xcloud.tclnative.ui.dashboard

import android.content.Context

/** Restarting an already-rendered game is an explicit player action, never an automatic codec switch. */
object VideoCodecFallbackDialog {
    fun show(context: Context,retry: () -> Unit,leave: () -> Unit): ConsoleDialog = ConsoleDialogs.message(
        context,"Decoder experimental indisponível",
        "Não foi possível continuar com o codec experimental. Reabrir em H.264 encerra esta sessão e pode voltar à fila. O progresso ainda não salvo pode ser perdido.",
        listOf(ConsoleAction("Reabrir em H.264",retry),ConsoleAction("Voltar ao catálogo",leave)),onCancel=leave)
}
