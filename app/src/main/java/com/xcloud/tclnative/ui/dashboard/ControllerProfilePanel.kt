package com.xcloud.tclnative.ui.dashboard

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.view.InputDevice
import com.xcloud.tclnative.settings.ControllerProfile
import com.xcloud.tclnative.settings.ControllerProfileStore
import com.xcloud.tclnative.settings.StickCurve
import com.xcloud.tclnative.webrtc.UsbControllerRumble

class ControllerProfilePanel(private val context: Context,private val store: ControllerProfileStore,private val changed: () -> Unit={}) {
    fun show(back: () -> Unit): ConsoleDialog {
        val devices=InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter(UsbControllerRumble::isGamepad)
        if(devices.isEmpty()) return ConsoleDialogs.message(context,UiText.text("Perfis de controle"),UiText.text("Conecte um controle e pressione um botão para o Android identificá-lo."),
            listOf(ConsoleAction(UiText.text("Voltar"),back)),onCancel=back)
        return ConsoleDialogs.menu(context,UiText.text("Perfis de controle"),devices.map { device -> ConsoleAction(device.name) { edit(device,back) } },onBack=back)
    }
    fun edit(device: InputDevice,back: () -> Unit): ConsoleDialog {
        val key=store.key(device);val value=store.read(key)
        fun save(profile: ControllerProfile) { store.save(key,profile);changed();edit(device,back) }
        fun choices(title: String,values: List<Pair<String,Boolean>>,apply: (Int)->Unit) {
            ConsoleDialog(context,title).apply {
                values.forEachIndexed { i,(label,selected) -> val row=addMenuItem(UiText.text("{0}  {1}",UiText.nested(if(selected) "✓" else "○"),UiText.nested(label))) { apply(i) };if(selected) focusFirst(row) }
                addAction(UiText.text("Voltar")) { edit(device,back) };setOnCancelListener { edit(device,back) };show()
            }
        }
        val deadzones=listOf(0,2,5,10,15,20,30,40);val sensitivity=listOf(25,50,75,100,125,150,175,200)
        return ConsoleDialogs.menu(context,UiText.text("Ajustar {0}",UiText.nested(device.name)),listOf(
            ConsoleAction(UiText.text("Zona morta esquerda: {0}%",UiText.nested(value.leftDeadzone))) { choices(UiText.text("Zona morta esquerda"),deadzones.map { "$it%" to (it==value.leftDeadzone) }) { save(value.copy(leftDeadzone=deadzones[it])) } },
            ConsoleAction(UiText.text("Zona morta direita: {0}%",UiText.nested(value.rightDeadzone))) { choices(UiText.text("Zona morta direita"),deadzones.map { "$it%" to (it==value.rightDeadzone) }) { save(value.copy(rightDeadzone=deadzones[it])) } },
            ConsoleAction(UiText.text("Sensibilidade esquerda: {0}%",UiText.nested(value.leftSensitivity))) { choices(UiText.text("Sensibilidade esquerda"),sensitivity.map { "$it%" to (it==value.leftSensitivity) }) { save(value.copy(leftSensitivity=sensitivity[it])) } },
            ConsoleAction(UiText.text("Sensibilidade direita: {0}%",UiText.nested(value.rightSensitivity))) { choices(UiText.text("Sensibilidade direita"),sensitivity.map { "$it%" to (it==value.rightSensitivity) }) { save(value.copy(rightSensitivity=sensitivity[it])) } },
            ConsoleAction(UiText.text("Curva: {0}",UiText.nested(value.curve.label))) { choices(UiText.text("Curva dos analógicos"),StickCurve.entries.map { it.label to (it==value.curve) }) { save(value.copy(curve=StickCurve.entries[it])) } },
            ConsoleAction(UiText.text("Inverter Y esquerdo: {0}",UiText.nested(if(value.invertLeftY) UiText.text("Sim") else UiText.text("Não")))) { save(value.copy(invertLeftY=!value.invertLeftY)) },
            ConsoleAction(UiText.text("Inverter Y direito: {0}",UiText.nested(if(value.invertRightY) UiText.text("Sim") else UiText.text("Não")))) { save(value.copy(invertRightY=!value.invertRightY)) },
            ConsoleAction(UiText.text("Zona morta dos gatilhos: {0}%",UiText.nested(value.triggerDeadzone))) { choices(UiText.text("Gatilhos"),deadzones.map { "$it%" to (it==value.triggerDeadzone) }) { save(value.copy(triggerDeadzone=deadzones[it])) } },
            ConsoleAction(UiText.text("Trocar LT e RT: {0}",UiText.nested(if(value.swapTriggers) UiText.text("Sim") else UiText.text("Não")))) { save(value.copy(swapTriggers=!value.swapTriggers)) },
            ConsoleAction(UiText.text("Remapear botões")) {
                ConsoleDialogs.menu(context,UiText.text("Remapear — Menu + View reservado"),BUTTONS.map { (source,label) ->
                    ConsoleAction(UiText.text("{0} → {1}",UiText.nested(label),UiText.nested(BUTTONS[value.mapping[source] ?: source] ?: UiText.text("Desativado")))) {
                        val targets=listOf(0 to UiText.text("Desativado"))+BUTTONS.toList()
                        choices(UiText.text("Destino de {0}",UiText.nested(label)),targets.map { it.second to (it.first==(value.mapping[source] ?: source)) }) {
                            save(value.copy(mapping=value.mapping+(source to targets[it].first)))
                        }
                    }
                },onBack={ edit(device,back) })
            },
            ConsoleAction(UiText.text("Restaurar este controle")) { store.reset(key);changed();edit(device,back) }
        ),onBack={ show(back) })
    }
    companion object {
        val BUTTONS=linkedMapOf(2 to "Xbox",4 to "Menu",8 to "View",16 to "A",32 to "B",64 to "X",128 to "Y",
            256 to "↑",512 to "↓",1024 to "←",2048 to "→",4096 to "LB",8192 to "RB",16384 to "L3",32768 to "R3")
    }
}
