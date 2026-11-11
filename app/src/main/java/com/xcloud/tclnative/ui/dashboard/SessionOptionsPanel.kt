package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import com.xcloud.tclnative.auth.CloudRegion
import com.xcloud.tclnative.settings.SessionOptions
import com.xcloud.tclnative.settings.SessionOptionsStore

class SessionOptionsPanel(private val context: Context,private val store: SessionOptionsStore,
    private val regions: List<CloudRegion>,private val gameId: String?=null,private val gameName: String?=null,
    private val back: () -> Unit) {
    fun show(): ConsoleDialog {
        val value=store.read(gameId)
        val title=gameName?.let { "Opções: $it" } ?: "Padrão das partidas"
        val actions=mutableListOf(
            ConsoleAction("Bitrate máximo: ${if(value.maxBitrateKbps==0) "Automático" else "${value.maxBitrateKbps/1000.0} Mbps"}") { bitrate() },
            ConsoleAction("Região: ${value.region.ifBlank { "Automático" }}") { region() },
            ConsoleAction("Idioma solicitado: ${SessionOptions.LOCALES[value.locale]}") { locale() })
        if(gameId!=null) actions.add(ConsoleAction("Usar o padrão das partidas") { store.reset(gameId);show() })
        return ConsoleDialogs.menu(context,title,actions,onBack=back)
    }
    private fun choices(title: String,description: String,rows: List<Pair<String,Boolean>>,select: (Int)->Unit): ConsoleDialog =
        ConsoleDialog(context,title).apply {
            body.addView(text(description,20,color=DashboardTheme.muted))
            rows.forEachIndexed { index,(label,selected) ->
                val row=addMenuItem("${if(selected) "✓" else "○"}  $label") { select(index);this@SessionOptionsPanel.show() }
                if(selected) focusFirst(row)
            }
            addAction("Voltar") { this@SessionOptionsPanel.show() };setOnCancelListener { this@SessionOptionsPanel.show() };show()
        }
    fun bitrate(): ConsoleDialog {
        val current=store.read(gameId)
        val values=listOf(0,1000,2000,3000,5000,6000,8000,10000,12000,15000,20000,30000,50000)
        return choices("Bitrate máximo","Automático mantém o orçamento do perfil de vídeo. Um teto manual é independente da resolução. Valores maiores exigem mais rede e podem piorar a estabilidade. O Xbox decide o bitrate recebido. Vale na próxima partida.",
            values.map { (if(it==0) "Automático" else "${it/1000.0} Mbps") to (it==current.maxBitrateKbps) }) {
            store.save(gameId,current.copy(maxBitrateKbps=values[it]))
        }
    }
    fun region(): ConsoleDialog {
        val current=store.read(gameId)
        val values=listOf("" to "Automático — região padrão da conta")+regions.map { it.name to it.name }
        return choices("Região do servidor","Somente as regiões oferecidas pelo Xbox a esta conta. A escolha solicita a sessão nessa região; não prevê o ping ou a fila do jogo. Se uma região salva deixar de ser oferecida, o app usa a padrão e informa no diagnóstico. Vale na próxima partida.",
            values.map { it.second to (it.first==current.region) }) { store.save(gameId,current.copy(region=values[it].first)) }
    }
    fun locale(): ConsoleDialog {
        val current=store.read(gameId);val values=SessionOptions.LOCALES.entries.toList()
        return choices("Idioma da sessão","A interface do app continua em português. O idioma é solicitado ao Xbox; alguns jogos usam suas próprias configurações ou podem ignorá-lo. Vale na próxima partida.",
            values.map { it.value to (it.key==current.locale) }) { store.save(gameId,current.copy(locale=values[it].key)) }
    }
}
