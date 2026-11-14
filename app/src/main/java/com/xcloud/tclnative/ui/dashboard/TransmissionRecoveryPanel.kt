package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.i18n.UiText

object TransmissionRecoveryPanel {
    fun show(context: Context,repair: () -> Unit,reload: () -> Unit,back: () -> Unit): ConsoleDialog =
        ConsoleDialog(context,UiText.text("Recuperar transmissão")).apply {
            body.addView(text(UiText.text("Recuperar imagem reinicia a exibição e solicita um novo quadro. Recarregar transmissão tenta refazer áudio, vídeo e controles na sessão Xbox atual. O jogo continua rodando durante a tentativa."),19,color=DashboardTheme.muted))
            addMenuItem(UiText.text("Recuperar imagem"),repair)
            addMenuItem(UiText.text("Recarregar transmissão — experimental")) {
                ConsoleDialogs.message(context,UiText.text("Recarregar transmissão"),
                    UiText.text("A conexão atual será substituída. A tentativa usa a mesma sessão Xbox e não inicia outra partida nem entra em uma nova fila. Se o Xbox já encerrou a sessão, não será possível recuperá-la. O microfone voltará mudo."),
                    listOf(ConsoleAction(UiText.text("Recarregar transmissão"),reload),ConsoleAction(UiText.text("Voltar")) { show(context,repair,reload,back) }),
                    onCancel={ show(context,repair,reload,back) })
            }
            addAction(UiText.text("Voltar"),back);setOnCancelListener { back() };show()
        }
    fun failed(context: Context,retry: () -> Unit,disconnect: () -> Unit,back: () -> Unit): ConsoleDialog =
        ConsoleDialogs.message(context,UiText.text("Transmissão interrompida"),
            UiText.text("A transmissão não voltou. Você pode tentar reconectar enquanto a sessão Xbox estiver ativa. A tentativa não abre outra partida ou uma nova fila."),
            listOf(ConsoleAction(UiText.text("Tentar reconectar"),retry),ConsoleAction(UiText.text("Menu da partida"),back),ConsoleAction(UiText.text("Encerrar partida"),disconnect)),onCancel=back)
}
