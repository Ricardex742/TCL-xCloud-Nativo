package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

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
            ConsoleAction(UiText.text("Layout: {0}",UiText.nested(if(settings.preset==null) UiText.text("Automático • {0}",UiText.nested(automatic.label)) else effective.label))) { choosePreset() },
            ConsoleAction(UiText.text("Editar posição, tamanho e ações")) { edit() },
            ConsoleAction(UiText.text("Tamanho dos controles: {0}%",UiText.nested(settings.scale))) { chooseValue(UiText.text("Tamanho dos controles"),listOf(75,90,100,115,130,150),settings.scale) { save(settings.copy(scale=it)) } },
            ConsoleAction(UiText.text("Opacidade: {0}%",UiText.nested(settings.opacity))) { chooseValue(UiText.text("Opacidade"),listOf(25,40,55,62,75,90,100),settings.opacity) { save(settings.copy(opacity=it)) } }
        )
        if(effective.shooter) {
            actions+=ConsoleAction(UiText.text("Sensibilidade do arrasto: {0}%",UiText.nested(settings.sensitivity))) {
                chooseValue(UiText.text("Sensibilidade da câmera por arrasto"),listOf(40,60,100,160,220,300,450,650,900,1200),settings.sensitivity,UiText.text("Ajusta a resposta do arrasto no HUD. A velocidade máxima de giro também depende da sensibilidade de controle configurada dentro do jogo.")) { save(settings.copy(sensitivity=it)) }
            }
            actions+=ConsoleAction(UiText.text("Compensação da zona morta: {0}%",UiText.nested(settings.lookDeadzone))) {
                chooseValue(UiText.text("Compensação da zona morta da câmera"),listOf(0,8,12,18,25,35),settings.lookDeadzone) { save(settings.copy(lookDeadzone=it)) }
            }
        }
        if(effective.family==HudPreset.CALL_OF_DUTY) {
            actions+=ConsoleAction(UiText.text("Mirar ao atirar: {0}",UiText.nested(if(settings.aimAndFire) UiText.text("Ativado") else UiText.text("Desativado")))) {
                val dialog=ConsoleDialog(context,UiText.text("Mirar ao atirar"))
                dialog.body.addView(dialog.text(UiText.text("Ativado combina mira e disparo nos dois botões Atirar. Desativado mantém o disparo separado da mira. Aplicar restaura as ações desses botões e mantém suas posições."),20,color=DashboardTheme.muted))
                listOf(false,true).forEach { enabled ->
                    val row=dialog.addMenuItem(UiText.text("{0} {1}",UiText.nested(if(enabled==settings.aimAndFire) "✓" else "○"),UiText.nested(if(enabled) UiText.text("Ativado — mirar e atirar") else UiText.text("Desativado — somente atirar")))) { save(settings.withAimAndFire(enabled)) }
                    if(enabled==settings.aimAndFire) dialog.focusFirst(row)
                }
                dialog.addAction(UiText.text("Voltar")) { show() };dialog.setOnCancelListener { show() };dialog.show()
            }
            actions+=ConsoleAction(UiText.text("Arrastar analógico para correr: {0}",UiText.nested(if(settings.sprintGesture) UiText.text("Ativado") else UiText.text("Desativado")))) {
                val dialog=ConsoleDialog(context,UiText.text("Corrida pelo analógico"))
                dialog.body.addView(dialog.text(UiText.text("Arraste o analógico de movimento para cima, na direção de Correr, além da borda do analógico. Recuar solta o comando de corrida; soltar o dedo também zera o movimento. As opções do jogo definem o comportamento da corrida. O botão Correr também funciona por toque."),20,color=DashboardTheme.muted))
                listOf(false,true).forEach { enabled ->
                    val row=dialog.addMenuItem(UiText.text("{0} {1}",UiText.nested(if(enabled==settings.sprintGesture) "✓" else "○"),UiText.nested(if(enabled) UiText.text("Ativado") else UiText.text("Desativado")))) { save(settings.copy(sprintGesture=enabled)) }
                    if(enabled==settings.sprintGesture) dialog.focusFirst(row)
                }
                dialog.addAction(UiText.text("Voltar")) { show() };dialog.setOnCancelListener { show() };dialog.show()
            }
        }
        if(effective.football) {
            actions+=ConsoleAction(UiText.text("Esquema de futebol: {0}",UiText.nested(settings.footballScheme.label))) {
                val dialog=ConsoleDialog(context,UiText.text("Esquema de controle do futebol"))
                dialog.body.addView(dialog.text(UiText.text("Escolha o mesmo esquema usado dentro do jogo. Aplicar restaura as ações e os gestos dos botões de futebol, mantendo as posições e os tamanhos personalizados."),20,color=DashboardTheme.muted))
                FootballScheme.entries.forEach { scheme ->
                    val row=dialog.addMenuItem(UiText.text("{0} {1}",UiText.nested(if(scheme==settings.footballScheme) "✓" else "○"),UiText.nested(scheme.label))) { save(settings.withFootballScheme(scheme)) }
                    if(scheme==settings.footballScheme) dialog.focusFirst(row)
                }
                dialog.addAction(UiText.text("Voltar")) { show() };dialog.setOnCancelListener { show() };dialog.show()
            }
            actions+=ConsoleAction(UiText.text("Mostrar HUD de ataque")) { page(effective.family) }
            actions+=ConsoleAction(UiText.text("Mostrar HUD de defesa")) { page(if(effective.family==HudPreset.EA_FC) HudPreset.EA_FC_DEFENSE else HudPreset.FOOTBALL_DEFENSE) }
        }
        actions+=ConsoleAction(UiText.text("Restaurar HUD deste jogo")) { save(HudSettings(footballScheme=settings.footballScheme)) }
        actions+=ConsoleAction(UiText.text("Guia de gestos e modos")) {
            ConsoleDialogs.message(context,UiText.text("Gestos e comandos combinados"),
                UiText.text("No futebol, toque em ATAQUE / DEFESA para trocar os comandos. A troca é manual. No chute: arraste para baixo para colocado, para cima para cavadinha e para os lados para superchute. Marcar combina RT + A.\n\nNo editor, Ação permite combinar botões; Gestos configura cada direção de arrasto. Os comandos seguem o esquema do seu jogo.\n\nNo CoD, segure e arraste Mirar ou Atirar para mover a câmera. Mirar ao atirar é opcional. Arrastar o analógico para cima, na direção de Correr, ativa a corrida quando essa opção está ligada.\n\nNos jogos de tiro, aumente a sensibilidade do HUD e ajuste a compensação da zona morta. A velocidade máxima de giro também depende da sensibilidade de controle configurada dentro do jogo."),
                listOf(ConsoleAction(UiText.text("Voltar")) { show() }),onCancel={ show() })
        }
        actions+=ConsoleAction(UiText.text("Ocultar HUD"),hide)
        return ConsoleDialogs.menu(context,"HUD • ${game.name}",actions)
    }
    private fun save(settings: HudSettings) {
        try { store.save(game.titleId,settings); changed(settings); show() }
        catch(error: java.io.IOException) { Toast.makeText(context,error.message,Toast.LENGTH_LONG).show() }
    }
    private fun choosePreset() {
        val current=store.read(game.titleId)
        val options=listOf<HudPreset?>(null)+HudPreset.entries.filter { it.family==it }
        val dialog=ConsoleDialog(context,UiText.text("Layout dos controles"))
        dialog.body.addView(dialog.text(UiText.text("Os comandos usam o mapeamento padrão de controle Xbox. Você pode ajustar cada ação no editor e nas configurações do jogo."),20,color=DashboardTheme.muted),LinearLayout.LayoutParams(-1,-2))
        options.forEach { preset ->
            val label=preset?.label ?: UiText.text("Automático • {0}",UiText.nested(GameHud.automatic(game.titleId,game.name).label))
            val row=dialog.addMenuItem(UiText.text("{0} {1}",UiText.nested(if(preset==current.preset) "✓" else "○"),UiText.nested(label))) {
                save(current.copy(preset=preset,placements=emptyMap()))
            }
            if(preset==current.preset) dialog.focusFirst(row)
        }
        dialog.addAction(UiText.text("Voltar")) { show() }; dialog.setOnCancelListener { show() }; dialog.show()
    }
    private fun chooseValue(title: String,values: List<Int>,current: Int,description: String?=null,select: (Int) -> Unit) {
        val dialog=ConsoleDialog(context,title)
        description?.let { dialog.body.addView(dialog.text(it,20,color=DashboardTheme.muted)) }
        values.forEach { value ->
            val row=dialog.addMenuItem("${if(value==current) "✓" else "○"} $value%") { select(value) }
            if(value==current) dialog.focusFirst(row)
        }
        dialog.addAction(UiText.text("Voltar")) { show() }; dialog.setOnCancelListener { show() }; dialog.show()
    }
}
