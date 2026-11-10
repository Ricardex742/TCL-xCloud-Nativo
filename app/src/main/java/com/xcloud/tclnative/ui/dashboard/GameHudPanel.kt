package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.LinearLayout
import android.widget.Toast
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*

class GameHudPanel(private val context: Context,private val game: GameTitle,private val store: GameHudStore,
                   private val changed: (HudSettings) -> Unit,private val edit: () -> Unit,
                   private val hide: () -> Unit,private val page: (HudPreset) -> Unit) {
    fun show(): ConsoleDialog {
        val settings=store.read(game.titleId)
        val automatic=GameHud.automatic(game.titleId,game.name)
        val effective=settings.preset ?: automatic
        val actions=mutableListOf(
            ConsoleAction("Layout: ${if(settings.preset==null) "Automático • ${automatic.label}" else effective.label}") { choosePreset() },
            ConsoleAction("Editar posição, tamanho e ações") { edit() },
            ConsoleAction("Tamanho dos controles: ${settings.scale}%") { chooseValue("Tamanho dos controles",listOf(75,90,100,115,130,150),settings.scale) { save(settings.copy(scale=it)) } },
            ConsoleAction("Opacidade: ${settings.opacity}%") { chooseValue("Opacidade",listOf(25,40,55,62,75,90,100),settings.opacity) { save(settings.copy(opacity=it)) } }
        )
        if(effective.shooter) {
            actions+=ConsoleAction("Sensibilidade do arrasto: ${settings.sensitivity}%") {
                chooseValue("Sensibilidade da câmera por arrasto",listOf(40,60,100,160,220,300,450,650,900,1200),settings.sensitivity) { save(settings.copy(sensitivity=it)) }
            }
            actions+=ConsoleAction("Compensação da zona morta: ${settings.lookDeadzone}%") {
                chooseValue("Compensação da zona morta da câmera",listOf(0,8,12,18,25,35),settings.lookDeadzone) { save(settings.copy(lookDeadzone=it)) }
            }
        }
        if(effective.football) {
            actions+=ConsoleAction("Esquema de futebol: ${settings.footballScheme.label}") {
                ConsoleDialogs.menu(context,"Esquema de controle do futebol",FootballScheme.entries.map { scheme ->
                    ConsoleAction(scheme.label) { save(settings.copy(footballScheme=scheme)) }
                },onBack={ show() })
            }
            actions+=ConsoleAction("Mostrar HUD de ataque") { page(effective.family) }
            actions+=ConsoleAction("Mostrar HUD de defesa") { page(if(effective.family==HudPreset.EA_FC) HudPreset.EA_FC_DEFENSE else HudPreset.FOOTBALL_DEFENSE) }
        }
        actions+=ConsoleAction("Restaurar HUD deste jogo") { save(HudSettings()) }
        actions+=ConsoleAction("Guia de gestos e modos") {
            ConsoleDialogs.message(context,"Gestos e comandos combinados",
                "No futebol, toque em ATAQUE / DEFESA para trocar os comandos. A troca é manual. No chute: arraste para baixo para colocado, para cima para cavadinha e para os lados para superchute. Marcar combina RT + A.\n\nNo editor, Ação permite combinar botões; Gestos configura cada direção de arrasto. Os comandos seguem o esquema do seu jogo.\n\nNos jogos de tiro, aumente a sensibilidade do HUD e ajuste a compensação da zona morta. A velocidade máxima de giro também depende da sensibilidade de controle configurada dentro do jogo.",
                listOf(ConsoleAction("Voltar") { show() }),onCancel={ show() })
        }
        actions+=ConsoleAction("Ocultar HUD",hide)
        return ConsoleDialogs.menu(context,"HUD • ${game.name}",actions)
    }
    private fun save(settings: HudSettings) {
        try { store.save(game.titleId,settings); changed(settings); show() }
        catch(error: java.io.IOException) { Toast.makeText(context,error.message,Toast.LENGTH_LONG).show() }
    }
    private fun choosePreset() {
        val current=store.read(game.titleId)
        val options=listOf<HudPreset?>(null)+HudPreset.entries.filter { it.family==it }
        val dialog=ConsoleDialog(context,"Layout dos controles")
        dialog.body.addView(dialog.text("Os comandos usam o mapeamento padrão de controle Xbox. Você pode ajustar cada ação no editor e nas configurações do jogo.",20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2))
        options.forEach { preset ->
            val label=preset?.label ?: "Automático • ${GameHud.automatic(game.titleId,game.name).label}"
            val row=dialog.addMenuItem("${if(preset==current.preset) "✓" else "○"} $label") {
                save(current.copy(preset=preset,placements=emptyMap()))
            }
            if(preset==current.preset) dialog.focusFirst(row)
        }
        dialog.addAction("Voltar") { show() }; dialog.setOnCancelListener { show() }; dialog.show()
    }
    private fun chooseValue(title: String,values: List<Int>,current: Int,select: (Int) -> Unit) {
        val dialog=ConsoleDialog(context,title)
        values.forEach { value ->
            val row=dialog.addMenuItem("${if(value==current) "✓" else "○"} $value%") { select(value) }
            if(value==current) dialog.focusFirst(row)
        }
        dialog.addAction("Voltar") { show() }; dialog.setOnCancelListener { show() }; dialog.show()
    }
}
