package com.xcloud.tclnative.webrtc

import com.xcloud.tclnative.i18n.UiText

data class UsbControllerDescriptor(val source: ControllerSource.Usb,val input: Int,val output: Int,
    val packetSize: Int,val androidId: Int?=null) {
    val label get()="${source.name} • %04x:%04x • ${source.intf}".format(source.vendor,source.product)
}
enum class UsbControllerStatus {
    OFF,PERMISSION,OPENING,READY,DENIED,BUSY,UNAVAILABLE,FAILED,ANDROID_REQUIRED,DISCONNECTED;
    val label get()=when(this) {
        OFF -> UiText.text("USB direto desativado")
        PERMISSION -> UiText.text("Aguardando autorização USB")
        OPENING -> UiText.text("Abrindo controle USB")
        READY -> UiText.text("USB direto ativo")
        DENIED -> UiText.text("Autorização USB negada")
        BUSY -> UiText.text("Interface USB ocupada")
        UNAVAILABLE -> UiText.text("Receptor compatível não encontrado")
        FAILED -> UiText.text("Falha na comunicação USB")
        ANDROID_REQUIRED -> UiText.text("USB direto requer Android 8 ou posterior")
        DISCONNECTED -> UiText.text("Controle USB desconectado")
    }
}
data class UsbControllerSnapshot(val status: UsbControllerStatus=UsbControllerStatus.OFF,
    val source: ControllerSource.Usb?=null,val packets: Long=0,val valid: Long=0,val ignored: Long=0,
    val writes: Long=0,val state: GamepadState=GamepadState(),val detail: String="",
    val capture: List<String> = emptyList(),val deliveryMeanMs: Double=0.0,val deliveryPeakMs: Long=0,
    val coalesced: Long=0,val overflows: Long=0)
interface UsbControllerPort {
    fun candidates(): List<UsbControllerDescriptor>
    fun snapshot(): UsbControllerSnapshot
    fun enable(candidate: UsbControllerDescriptor,exclusive: Boolean=false)
    fun disable(done: () -> Unit={})
    fun test()
    fun capture()
    fun descriptors(): String = candidates().joinToString("\n") {
        "${it.label} • ff/5d/01 • IN %02x OUT %02x • ${it.packetSize} B • Android ${it.androidId ?: "—"}".format(it.input,it.output)
    }
}
