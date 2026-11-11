package com.xcloud.tclnative.ui.dashboard

import android.content.Context
import android.view.InputDevice
import com.xcloud.tclnative.settings.ControllerProfile
import com.xcloud.tclnative.settings.ControllerProfileStore
import com.xcloud.tclnative.settings.StickCurve
import com.xcloud.tclnative.webrtc.UsbControllerRumble

class ControllerProfilePanel(private val context: Context,private val store: ControllerProfileStore,private val changed: () -> Unit={}) {
    fun show(back: () -> Unit): ConsoleDialog {
        val devices=InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter(UsbControllerRumble::isGamepad)
        if(devices.isEmpty()) return ConsoleDialogs.message(context,"Perfis de controle","Conecte um controle e pressione um botão para o Android identificá-lo.",
            listOf(ConsoleAction("Voltar",back)),onCancel=back)
        return ConsoleDialogs.menu(context,"Perfis de controle",devices.map { device -> ConsoleAction(device.name) { edit(device,back) } },onBack=back)
    }
    fun edit(device: InputDevice,back: () -> Unit): ConsoleDialog {
        val key=store.key(device);val value=store.read(key)
        fun save(profile: ControllerProfile) { store.save(key,profile);changed();edit(device,back) }
        fun choices(title: String,values: List<Pair<String,Boolean>>,apply: (Int)->Unit) {
            ConsoleDialog(context,title).apply {
                values.forEachIndexed { i,(label,selected) -> val row=addMenuItem("${if(selected) "✓" else "○"}  $label") { apply(i) };if(selected) focusFirst(row) }
                addAction("Voltar") { edit(device,back) };setOnCancelListener { edit(device,back) };show()
            }
        }
        val deadzones=listOf(0,2,5,10,15,20,30,40);val sensitivity=listOf(25,50,75,100,125,150,175,200)
        return ConsoleDialogs.menu(context,"Ajustar ${device.name}",listOf(
            ConsoleAction("Zona morta esquerda: ${value.leftDeadzone}%") { choices("Zona morta esquerda",deadzones.map { "$it%" to (it==value.leftDeadzone) }) { save(value.copy(leftDeadzone=deadzones[it])) } },
            ConsoleAction("Zona morta direita: ${value.rightDeadzone}%") { choices("Zona morta direita",deadzones.map { "$it%" to (it==value.rightDeadzone) }) { save(value.copy(rightDeadzone=deadzones[it])) } },
            ConsoleAction("Sensibilidade esquerda: ${value.leftSensitivity}%") { choices("Sensibilidade esquerda",sensitivity.map { "$it%" to (it==value.leftSensitivity) }) { save(value.copy(leftSensitivity=sensitivity[it])) } },
            ConsoleAction("Sensibilidade direita: ${value.rightSensitivity}%") { choices("Sensibilidade direita",sensitivity.map { "$it%" to (it==value.rightSensitivity) }) { save(value.copy(rightSensitivity=sensitivity[it])) } },
            ConsoleAction("Curva: ${value.curve.label}") { choices("Curva dos analógicos",StickCurve.entries.map { it.label to (it==value.curve) }) { save(value.copy(curve=StickCurve.entries[it])) } },
            ConsoleAction("Inverter Y esquerdo: ${if(value.invertLeftY) "Sim" else "Não"}") { save(value.copy(invertLeftY=!value.invertLeftY)) },
            ConsoleAction("Inverter Y direito: ${if(value.invertRightY) "Sim" else "Não"}") { save(value.copy(invertRightY=!value.invertRightY)) },
            ConsoleAction("Zona morta dos gatilhos: ${value.triggerDeadzone}%") { choices("Gatilhos",deadzones.map { "$it%" to (it==value.triggerDeadzone) }) { save(value.copy(triggerDeadzone=deadzones[it])) } },
            ConsoleAction("Trocar LT e RT: ${if(value.swapTriggers) "Sim" else "Não"}") { save(value.copy(swapTriggers=!value.swapTriggers)) },
            ConsoleAction("Remapear botões") {
                ConsoleDialogs.menu(context,"Remapear — Menu + View reservado",BUTTONS.map { (source,label) ->
                    ConsoleAction("$label → ${BUTTONS[value.mapping[source] ?: source] ?: "Desativado"}") {
                        val targets=listOf(0 to "Desativado")+BUTTONS.toList()
                        choices("Destino de $label",targets.map { it.second to (it.first==(value.mapping[source] ?: source)) }) {
                            save(value.copy(mapping=value.mapping+(source to targets[it].first)))
                        }
                    }
                },onBack={ edit(device,back) })
            },
            ConsoleAction("Restaurar este controle") { store.reset(key);changed();edit(device,back) }
        ),onBack={ show(back) })
    }
    companion object {
        val BUTTONS=linkedMapOf(2 to "Xbox",4 to "Menu",8 to "View",16 to "A",32 to "B",64 to "X",128 to "Y",
            256 to "↑",512 to "↓",1024 to "←",2048 to "→",4096 to "LB",8192 to "RB",16384 to "L3",32768 to "R3")
    }
}
