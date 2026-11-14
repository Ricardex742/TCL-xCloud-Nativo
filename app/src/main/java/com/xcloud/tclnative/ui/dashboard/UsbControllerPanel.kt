package com.xcloud.tclnative.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.webrtc.*
import java.util.Locale

/** Live and remote-friendly: commands can be diagnosed before starting a cloud game. */
class UsbControllerPanel(private val context: Context,private val port: UsbControllerPort,
    private val activate: (UsbControllerDescriptor,Boolean) -> Unit={ candidate,exclusive -> port.enable(candidate,exclusive) },
    private val androidSnapshot: () -> AndroidControllerMonitor.Snapshot=AndroidControllerMonitor::snapshot) {
    fun show(back: () -> Unit): ConsoleDialog {
        val dialog=ConsoleDialog(context,UiText.text("USB direto e teste de comandos"))
        dialog.body.addView(dialog.text(UiText.text("Modo opcional e experimental para Xbox 360/XInput. Começa desativado ao abrir o app. Autorize o receptor usando o controle remoto ou o toque."),18))
        val status=dialog.text("",22,true)
        val details=dialog.text("",17).apply { typeface=android.graphics.Typeface.MONOSPACE }
        dialog.body.addView(status);dialog.body.addView(details)
        fun action(label: String,run: () -> Unit) = dialog.addPersistentAction(label,run).apply {
            maxLines=1;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,dialog.px(19).toFloat())
        }
        action(UiText.text("Ativar USB direto")) { choose(false) }
        action(UiText.text("Desativar")) { port.disable() }
        action(UiText.text("Testar vibração")) { port.test() }
        dialog.addMenuItem(UiText.text("Captura exclusiva — experimental")) { confirmExclusive { show(back) } }
        dialog.addMenuItem(UiText.text("Capturar pacotes por 10 segundos")) {
            port.capture();show(back);Toast.makeText(context,UiText.text("Captura limitada iniciada. Pressione os botões e mova os analógicos."),Toast.LENGTH_LONG).show()
        }
        dialog.addMenuItem(UiText.text("Ver relatório do controle")) { report { show(back) } }
        dialog.addAction(UiText.text("Voltar"),back)
        dialog.setOnCancelListener { back() }
        val handler=Handler(Looper.getMainLooper())
        val refresh=object: Runnable {
            override fun run() {
                if(!dialog.isShowing) return
                val snapshot=port.snapshot();status.text=snapshot.status.label
                details.text=describe(snapshot,androidSnapshot())
                handler.postDelayed(this,100)
            }
        }
        dialog.addCleanup { handler.removeCallbacks(refresh) }
        dialog.show();refresh.run();return dialog
    }
    private fun choose(exclusive: Boolean) {
        val items=port.candidates()
        if(items.isEmpty()) {
            ConsoleDialogs.message(context,UiText.text("Controle USB"),UiText.text("Nenhum receptor Xbox 360/XInput compatível foi encontrado. DInput, HID e Xbox One usam outros protocolos."));return
        }
        ConsoleDialogs.menu(context,UiText.text("Escolher receptor USB"),items.map { candidate ->
            ConsoleAction(candidate.label) { activate(candidate,exclusive) }
        })
    }
    private fun confirmExclusive(back: () -> Unit) {
        ConsoleDialogs.message(context,UiText.text("Captura exclusiva — experimental"),
            UiText.text("Se a interface estiver ocupada, o app tentará assumir o controle USB enquanto estiver ativo. A navegação do sistema por esse controle pode parar. Tenha o controle remoto disponível. Ao desativar, o app libera a interface; alguns aparelhos podem exigir reconectar o dongle."),
            listOf(ConsoleAction(UiText.text("Escolher receptor USB")) { back();choose(true) },ConsoleAction(UiText.text("Voltar"),back)),onCancel=back)
    }
    private fun report(back: () -> Unit) {
        val snapshot=port.snapshot()
        val report=snapshot.status.label+"\n"+describe(snapshot,androidSnapshot())+"\n\n"+port.descriptors()+"\n\n"+snapshot.capture.joinToString("\n")
        ConsoleDialogs.message(context,UiText.text("Relatório do controle"),report,
            listOf(ConsoleAction(UiText.text("Copiar detalhes")) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("USB/XInput",report));back()
            },ConsoleAction(UiText.text("Voltar"),back)),onCancel=back)
    }
    companion object {
        fun describe(s: UsbControllerSnapshot,a: AndroidControllerMonitor.Snapshot): String {
            val state=if(s.source!=null && s.valid>0) s.state else a.state
            val buttons=ControllerProfilePanel.BUTTONS.filterKeys { state.buttons and it!=0 }.values.joinToString(" + ")
            val identity=s.source?.let { "%04x:%04x • interface %d • %s".format(it.vendor,it.product,it.intf,it.name) } ?: a.name
            return UiText.text("Controle: {0}\nAndroid: {1} pressionados / {2} soltos / {3} movimentos\nUSB: {4} recebidos / {5} válidos / {6} ignorados / {7} saídas\nBotões: {8}\nLX/LY: {9} | RX/RY: {10} | LT/RT: {11}\nEntrega USB → app: {12} ms média / {13} ms pico\nMovimentos consolidados: {14} | Excesso de fila: {15}",
                UiText.nested(identity.ifBlank { "—" }),UiText.nested(a.down),UiText.nested(a.up),UiText.nested(a.moves),
                UiText.nested(s.packets),UiText.nested(s.valid),UiText.nested(s.ignored),UiText.nested(s.writes),
                UiText.nested("${buttons.ifBlank { "—" }} [%04x]".format(state.buttons)),UiText.nested(pair(state.leftX,state.leftY)),
                UiText.nested(pair(state.rightX,state.rightY)),UiText.nested(pair(state.leftTrigger,state.rightTrigger)),
                UiText.nested(String.format(Locale.US,"%.2f",s.deliveryMeanMs)),UiText.nested(s.deliveryPeakMs),UiText.nested(s.coalesced),UiText.nested(s.overflows))+
                (if(s.detail.isNotBlank()) "\n${s.detail}" else "")
        }
        private fun pair(a: Float,b: Float)=String.format(Locale.US,"%.3f / %.3f",a,b)
    }
}
