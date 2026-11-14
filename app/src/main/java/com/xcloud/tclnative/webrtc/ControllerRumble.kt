package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.InputDevice

/** Addresses the gamepad's actuator, never the phone's or TV remote's vibrator. */
class ControllerRumble(private val deviceFor: (Int) -> Int?, private val usb: UsbControllerRumble?=null,private val phoneVibrator: () -> Vibrator?={ null }) {
    var directRumble: ((RumbleCommand) -> Boolean)?=null
    var stopDirect: (() -> Unit)?=null
    var directDiagnostics: (() -> String?)?=null
    private val handler=Handler(Looper.getMainLooper())
    private val generation=IntArray(4)
    private val running=mutableMapOf<Int,Vibrator>()
    private var commands=0L
    private var usbCommands=0L
    private var androidCommands=0L
    var touchRumble=false
    var enabled=true
        set(value) { val changed=field!=value;field=value;if(changed && !value) stop() }
    fun play(command: RumbleCommand) {
        handler.post {
            if(!enabled) return@post
            if(directRumble?.invoke(command)==true) {
                commands++;usbCommands++
                running.remove(command.player)?.cancel();generation[command.player]++
                return@post
            }
            val id=deviceFor(command.player)
            if(id==null && (command.player!=0 || !touchRumble)) return@post
            commands++
            if(id!=null && usb?.accepts(id)==true) {
                running.remove(command.player)?.cancel()
                generation[command.player]++
                usbCommands++;usb.play(command,id)
                return@post
            }
            val vibrator=(if(id==null) phoneVibrator() else InputDevice.getDevice(id)?.vibrator)?.takeIf { it.hasVibrator() } ?: return@post
            androidCommands++
            val token=++generation[command.player]
            running.remove(command.player)?.cancel()
            if(command.intensity==0 || command.durationMs==0) return@post
            running[command.player]=vibrator
            val times=(command.repeat+1).coerceAtMost(16)
            fun pulse(left: Int) {
                if(!enabled || generation[command.player]!=token || deviceFor(command.player)!=id) return
                runCatching {
                    if(Build.VERSION.SDK_INT>=26) vibrator.vibrate(VibrationEffect.createOneShot(
                        command.durationMs.coerceAtMost(5000).toLong(),(command.intensity*255/100).coerceIn(1,255)))
                    else vibrator.vibrate(command.durationMs.coerceAtMost(5000).toLong())
                }
                if(left>1) handler.postDelayed({ pulse(left-1) },command.durationMs.coerceAtMost(5000)+command.delayMs.toLong())
            }
            handler.postDelayed({ pulse(times) },command.delayMs.toLong())
        }
    }
    fun stop() {
        generation.indices.forEach { generation[it]++ }
        handler.removeCallbacksAndMessages(null)
        running.values.forEach { runCatching { it.cancel() } }; running.clear()
        usb?.stop()
        stopDirect?.invoke()
    }
    fun diagnostics(): String = UiText.join(UiText.text("Vibração: {0} comandos com destino associado desde a abertura do app; {1} via Android; {2} encaminhados para USB.\n",UiText.nested(commands),UiText.nested(androidCommands),UiText.nested(usbCommands)),(directDiagnostics?.invoke() ?: usb?.status ?: "USB/XInput: desativado."))
    fun describe(): String {
        val devices=InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter {
            it.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD ||
                it.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK }
        return if(devices.isEmpty()) UiText.text("Nenhum controle conectado. Conecte o dongle e pressione um botão.")
        else devices.joinToString("\n\n") { UiText.text("{0} • {1}\nVibração exposta pelo Android: {2}",UiText.nested(it.name),UiText.nested("%04x:%04x".format(it.vendorId,it.productId)),UiText.nested(if(it.vibrator.hasVibrator()) UiText.text("sim") else UiText.text("não"))) } +
            UiText.text("\n\nO dongle e o driver do Android precisam oferecer vibração. Trocar o modo do controle para XInput pode mudar o suporte; o app não altera o firmware.")
    }
    fun test() {
        InputDevice.getDeviceIds().map { InputDevice.getDevice(it) }.filterNotNull().filter {
            it.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD || it.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
        }.forEach { device -> if(device.vibrator.hasVibrator()) runCatching {
            if(Build.VERSION.SDK_INT>=26) device.vibrator.vibrate(VibrationEffect.createOneShot(250,128)) else device.vibrator.vibrate(250)
        } }
    }
}
