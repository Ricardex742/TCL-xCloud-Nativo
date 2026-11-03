package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution

/** Explicit single-choice menus: opening a row does not change the selected value. */
class PlaybackSettingsPanel(
    private val context: Context,
    private val store: PlaybackSettingsStore,
    private val changed: (PlaybackSettings) -> Unit,
    private val subscription: () -> Unit,
    private val refresh: () -> Unit,
    private val logout: () -> Unit
) {
    fun show(): ConsoleDialog {
        val settings=store.read()
        return ConsoleDialogs.menu(context,"Configurações",listOf(
            ConsoleAction("Vídeo: ${settings.videoMode.label}") { chooseVideo() },
            ConsoleAction("Resolução: ${settings.resolution.label}") { chooseResolution() },
            ConsoleAction("Upscaling nativo: ${if(settings.nativeUpscaling) "Ativado" else "Desativado"}") { chooseUpscaling() },
            ConsoleAction("Gerenciar assinatura Microsoft",subscription),
            ConsoleAction("Atualizar catálogo",refresh),
            ConsoleAction("Trocar conta",logout)
        ))
    }
    private fun save(settings: PlaybackSettings) { store.save(settings); changed(settings); show() }
    private fun choices(title: String, description: String, entries: List<Pair<String,Boolean>>, select: (Int) -> Unit): ConsoleDialog {
        val dialog=ConsoleDialog(context,title)
        dialog.body.addView(dialog.text(description,20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2)
            .apply { bottomMargin=dialog.px(16) })
        entries.forEachIndexed { i,(label,selected) ->
            val row=dialog.addMenuItem("${if(selected) "✓" else "○"}  $label") { select(i) }
            row.isSelected=selected
            row.contentDescription="$label${if(selected) ", selecionado" else ""}"
            if (selected) dialog.focusFirst(row)
        }
        dialog.addAction("Voltar") { show() }
        dialog.setOnCancelListener { show() }
        dialog.show()
        return dialog
    }
    fun chooseVideo(): ConsoleDialog {
        val current=store.read()
        return choices("Modo de vídeo","Escolha o modo para a próxima partida. Menor atraso reduz buffers; adaptativo permite mais margem para oscilações da rede.",
            VideoMode.entries.map { it.label to (it==current.videoMode) }) { save(current.copy(videoMode=VideoMode.entries[it])) }
    }
    fun chooseResolution(): ConsoleDialog {
        val current=store.read()
        return choices("Resolução da transmissão","720p solicita menos banda e trabalho de decodificação. A resolução recebida depende do Xbox, do jogo e da conexão. Aplica-se à próxima partida.",
            StreamResolution.entries.map { it.label to (it==current.resolution) }) { save(current.copy(resolution=StreamResolution.entries[it])) }
    }
    fun chooseUpscaling(): ConsoleDialog {
        val current=store.read()
        return choices("Upscaling nativo","Ativado: preenche a tela com a escala da Surface Android. Desativado: mantém o vídeo sem ampliação na janela, podendo deixar barras. O AiPQ e o processamento da TV são ajustados na própria TV. Aplica-se à próxima partida.",
            listOf("Ativado — preencher a tela" to current.nativeUpscaling,"Desativado — tamanho original" to !current.nativeUpscaling)) {
            save(current.copy(nativeUpscaling=it==0))
        }
    }
}
