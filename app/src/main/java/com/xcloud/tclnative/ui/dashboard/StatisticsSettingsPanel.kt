package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.widget.Toast
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.settings.*

class StatisticsSettingsPanel(private val context: Context,private val store: StatisticsSettingsStore,
                              private val changed: () -> Unit={},private val back: () -> Unit={}) {
    fun show(): ConsoleDialog {
        val value=store.read()
        return ConsoleDialogs.menu(context,UiText.text("Estatísticas durante a partida"),listOf(
            ConsoleAction(UiText.text("Modo: {0}",UiText.nested(mode(value.detailed)))) {
                choose(UiText.text("Modo das estatísticas"),listOf(mode(false),mode(true)),if(value.detailed) 1 else 0) { save(value.copy(detailed=it==1)) }
            },
            ConsoleAction(UiText.text("Escolher indicadores")) { fields() },
            ConsoleAction(UiText.text("Posição: {0}",UiText.nested(value.position.label))) {
                choose(UiText.text("Posição das estatísticas"),StatisticsPosition.entries.map { it.label },value.position.ordinal) { save(value.copy(position=StatisticsPosition.entries[it])) }
            },
            ConsoleAction(UiText.text("Tamanho do texto: {0}",UiText.nested(value.textSize))) {
                val sizes=listOf(10,12,14,16,18,20,24)
                choose(UiText.text("Tamanho do texto"),sizes.map(Int::toString),sizes.indexOf(value.textSize)) { save(value.copy(textSize=sizes[it])) }
            },
            ConsoleAction(UiText.text("Opacidade do fundo: {0}%",UiText.nested(value.background))) {
                val levels=listOf(0,30,50,70,90,100)
                choose(UiText.text("Opacidade do fundo"),levels.map { "$it%" },levels.indexOf(value.background)) { save(value.copy(background=levels[it])) }
            },
            ConsoleAction(UiText.text("Restaurar padrão")) { save(StatisticsSettings());show() }
        ),back)
    }
    private fun mode(detailed: Boolean)=if(detailed) UiText.text("Completo") else UiText.text("Personalizado")
    private fun save(value: StatisticsSettings) { store.save(value);changed() }
    private fun choose(title: String,labels: List<String>,selected: Int,choose: (Int) -> Unit) {
        ConsoleDialog(context,title).apply {
            labels.forEachIndexed { i,label ->
                val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(i==selected) "✓" else "○"),UiText.nested(label))) { choose(i);this@StatisticsSettingsPanel.show() }
                if(i==selected) focusFirst(row)
            }
            addAction(UiText.text("Voltar")) { this@StatisticsSettingsPanel.show() }
            setOnCancelListener { this@StatisticsSettingsPanel.show() };show()
        }
    }
    private fun fields(): ConsoleDialog=ConsoleDialog(context,UiText.text("Escolher indicadores")).apply {
        body.addView(text(UiText.text("As escolhas valem no modo Personalizado e ficam salvas. Cada partida começa com as estatísticas ocultas. INFO/F10 ou o menu da partida ligam e desligam o painel."),18,color=DashboardTheme.muted))
        body.addView(text(UiText.text("FPS mede o vídeo decodificado; ping é o RTT da conexão. O agendador mede somente o envio de comandos."),17,color=DashboardTheme.muted))
        StatisticField.entries.forEach { field ->
            fun label()=UiText.text("{0}  {1}",UiText.nested(if(field in store.read().fields) "✓" else "○"),UiText.nested(field.label))
            val row=addMenuItem(label()) {}
            row.setOnClickListener {
                val value=store.read();val selected=value.fields.toMutableSet()
                if(!selected.add(field)) selected.remove(field)
                if(selected.isEmpty()) Toast.makeText(context,UiText.text("Selecione pelo menos um indicador."),Toast.LENGTH_SHORT).show()
                else { save(value.copy(fields=selected));row.text=label() }
            }
        }
        addAction(UiText.text("Voltar")) { this@StatisticsSettingsPanel.show() }
        setOnCancelListener { this@StatisticsSettingsPanel.show() };show()
    }
}
