package com.xcloud.tclnative.webrtc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import java.nio.ByteBuffer
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit opt-in USB output; no input reads, force-claim, firmware or device reconfiguration. */
class UsbControllerRumble(context: Context) : AutoCloseable {
    private val context=context.applicationContext
    private val manager=this.context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val main=Handler(Looper.getMainLooper())
    private val workerDelegate=lazy {
        ScheduledThreadPoolExecutor(1) { task -> Thread(task,"xcloud-usb-rumble") }.apply {
            removeOnCancelPolicy=true
        }
    }
    private val worker by workerDelegate
    private val generation=AtomicInteger()
    @Volatile private var closed=false
    @Volatile private var foreground=true
    @Volatile private var activeName: String?=null
    @Volatile private var activeInputId: Int?=null
    @Volatile var status="USB/XInput: desativado; ative o teste para autorizar um receptor."
        private set
    private var session: UsbXInputSession?=null // worker only
    private var pending: String?=null // main only
    private var completion: (() -> Unit)?=null
    private var registered=false
    private val action="${this.context.packageName}.USB_RUMBLE_PERMISSION"
    data class Candidate(val device: UsbDevice,val intf: UsbInterface,val output: UsbEndpoint) {
        val label: String get()="USB %04x:%04x • interface %d".format(device.vendorId,device.productId,intf.id)
    }
    private fun devices(): List<UsbDevice> = runCatching { manager?.deviceList?.values?.toList() ?: emptyList() }.getOrDefault(emptyList())
    fun candidates(): List<Candidate> = devices().mapNotNull { device ->
        // Composite devices with several possible gamepads require a dedicated protocol/router.
        val matches=(0 until device.interfaceCount).mapNotNull { index ->
            val intf=device.getInterface(index)
            val endpoints=(0 until intf.endpointCount).map(intf::getEndpoint)
            val output=endpoints.filter { it.direction==UsbConstants.USB_DIR_OUT && it.type==UsbConstants.USB_ENDPOINT_XFER_INT }
            if(UsbXInputProtocol.supported(intf.interfaceClass,intf.interfaceSubclass,intf.interfaceProtocol,intf.alternateSetting,
                endpoints.any { it.direction==UsbConstants.USB_DIR_IN && it.type==UsbConstants.USB_ENDPOINT_XFER_INT },
                output.map { it.maxPacketSize })) Candidate(device,intf,output.single()) else null
        }
        matches.singleOrNull()
    }
    fun describe(): String = status+"\n\n"+devices().joinToString("\n\n") { device ->
        "USB %04x:%04x".format(device.vendorId,device.productId)+"\n"+
            (0 until device.interfaceCount).joinToString("\n") { index ->
                val intf=device.getInterface(index)
                "Interface ${intf.id}: %02x/%02x/%02x".format(intf.interfaceClass,intf.interfaceSubclass,intf.interfaceProtocol)
            }
    }.ifBlank { "Nenhum dispositivo USB enumerado pelo Android." }+
        "\n\nO teste aceita apenas Xbox 360/XInput (ff/5d/01). HID, Xbox One e outros protocolos precisam de implementação própria. Uma transferência aceita não comprova que o motor vibrou."

    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) {
            if(closed) return
            if(intent.action==UsbManager.ACTION_USB_DEVICE_DETACHED) {
                if(activeName!=null && devices().none { it.deviceName==activeName }) disable()
                return
            }
            if(intent.action!=action) return
            val name=pending ?: return
            val device=devices().firstOrNull { it.deviceName==name }
            pending=null
            // Check current permission ourselves, independent of broadcast extras/mutability.
            if(device!=null && manager?.hasPermission(device)==true) activate(name)
            else finish("USB/XInput: autorização negada ou receptor desconectado.")
        }
    }
    fun enableAndTest(candidate: Candidate,done: () -> Unit) {
        if(closed || !foreground) { done();return }
        if(pending!=null) { done();return }
        completion=done
        if(manager==null) { finish("USB/XInput: serviço USB indisponível no Android.");return }
        if(Build.VERSION.SDK_INT<26) { finish("USB/XInput: o teste requer Android 8 ou posterior.");return }
        if(!registered) {
            val filter=IntentFilter(action).apply { addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) }
            try {
                if(Build.VERSION.SDK_INT>=33) context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver,filter)
            } catch(_: RuntimeException) { finish("USB/XInput: não foi possível acompanhar a autorização USB.");return }
            registered=true
        }
        if(manager?.hasPermission(candidate.device)==true) { activate(candidate.device.deviceName);return }
        pending=candidate.device.deviceName
        status="USB/XInput: aguardando autorização do Android."
        val intent=Intent(action).setPackage(context.packageName)
        val permission=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager?.requestPermission(candidate.device,permission) }
        catch(_: RuntimeException) { pending=null;finish("USB/XInput: não foi possível solicitar autorização.") }
    }
    private fun activate(name: String) {
        val token=generation.incrementAndGet()
        val report: (String) -> Unit = { message -> finish(message,token) }
        worker.queue.clear()
        worker.execute {
            disconnect()
            val candidate=candidates().firstOrNull { it.device.deviceName==name }
            if(candidate==null || closed || !foreground || generation.get()!=token) {
                report("USB/XInput: receptor indisponível.");return@execute
            }
            val connection=runCatching { manager?.openDevice(candidate.device) }.getOrNull()
            if(connection==null) { report("USB/XInput: não foi possível abrir o receptor.");return@execute }
            val io=object: UsbXInputSession.Io {
                override fun claim(force: Boolean)=connection.claimInterface(candidate.intf,force)
                override fun write(packet: ByteArray): Boolean {
                    if(Build.VERSION.SDK_INT<26) return false
                    val request=UsbRequest()
                    var completed=false
                    return try {
                        if(!request.initialize(connection,candidate.output)) false
                        else {
                            val buffer=ByteBuffer.allocateDirect(packet.size).apply { put(packet);flip() }
                            if(!request.queue(buffer)) false else {
                                completed=connection.requestWait(200)==request
                                completed && buffer.position()==packet.size
                            }
                        }
                    } catch(_: Exception) { false }
                    finally {
                        if(!completed) runCatching { request.cancel() }
                        runCatching { request.close() }
                    }
                }
                override fun release() { connection.releaseInterface(candidate.intf) }
                override fun close() { connection.close() }
            }
            val opened=UsbXInputSession.open(io)
            if(opened==null) {
                report("USB/XInput: interface ocupada ou recusada pelo driver. O controle continua sob controle do Android; acesso forçado desativado.")
                return@execute
            }
            if(closed || generation.get()!=token) { opened.close();return@execute }
            session=opened
            val matching=InputDevice.getDeviceIds().map(InputDevice::getDevice).filterNotNull().filter(::isGamepad)
            if(!opened.write(40,40)) {
                disconnect();report("USB/XInput: o receptor não aceitou o pacote de teste.");return@execute
            }
            if(closed || generation.get()!=token) { disconnect();return@execute }
            activeName=name
            activeInputId=UsbXInputProtocol.inputDeviceId(candidate.device.vendorId,candidate.device.productId,
                devices().map { it.vendorId to it.productId },matching.map { Triple(it.id,it.vendorId,it.productId) })
            worker.schedule({
                if(generation.get()==token && session===opened) {
                    if(!opened.write(0,0)) { disconnect();report("USB/XInput: falha ao encerrar o pulso; conexão liberada.") }
                    else report("USB/XInput: pulso de 250 ms enviado. "+
                        if(activeInputId==null) "Sem associação única ao controle Android; vibração da partida indisponível."
                        else "Via USB disponível para este controle enquanto o app estiver aberto. Confirme se houve vibração.")
                }
            },250,TimeUnit.MILLISECONDS)
        }
    }
    fun accepts(deviceId: Int): Boolean = !closed && foreground && activeName!=null && activeInputId==deviceId
    fun play(command: RumbleCommand,deviceId: Int) {
        if(!accepts(deviceId)) return
        val token=generation.incrementAndGet()
        worker.queue.clear() // Latest command replaces pending repeats/stops; never accumulate game events.
        worker.execute {
            if(generation.get()!=token || !accepts(deviceId)) return@execute
            val current=session ?: return@execute
            fun write(strong: Int,weak: Int): Boolean {
                if(current.write(strong,weak)) return true
                disconnect();status="USB/XInput: transferência falhou; conexão liberada.";return false
            }
            if(!write(0,0)) return@execute
            if(command.durationMs<=0 || (command.strong<=0 && command.weak<=0)) return@execute
            val duration=command.durationMs.coerceIn(1,5000).toLong()
            val delay=command.delayMs.coerceIn(0,5000).toLong()
            fun pulse(remaining: Int) {
                if(generation.get()!=token || !accepts(deviceId) || session!==current) return
                if(!write(command.strong,command.weak)) return
                worker.schedule({
                    if(generation.get()==token && session===current && write(0,0) && remaining>1)
                        worker.schedule({ pulse(remaining-1) },delay,TimeUnit.MILLISECONDS)
                },duration,TimeUnit.MILLISECONDS)
            }
            worker.schedule({ pulse((command.repeat+1).coerceIn(1,16)) },delay,TimeUnit.MILLISECONDS)
        }
    }
    fun stop() {
        if(!workerDelegate.isInitialized()) return
        generation.incrementAndGet()
        worker.queue.clear()
        if(!closed) worker.execute { if(session?.write(0,0)==false) disconnect() }
    }
    fun setForeground(value: Boolean) {
        foreground=value
        if(!value) stop()
    }
    fun disable() {
        generation.incrementAndGet();pending=null;completion=null
        activeName=null;activeInputId=null
        if(workerDelegate.isInitialized()) {
            worker.queue.clear()
            if(!closed) worker.execute { disconnect() }
        }
        status="USB/XInput: desativado."
    }
    private fun disconnect() {
        activeName=null;activeInputId=null
        runCatching { session?.close() };session=null
    }
    private fun finish(message: String,token: Int=generation.get()) {
        main.post {
            if(!closed && generation.get()==token) {
                status=message
                val done=completion;completion=null;done?.invoke()
            }
        }
    }
    override fun close() {
        if(closed) return
        closed=true;generation.incrementAndGet();pending=null;completion=null
        activeName=null;activeInputId=null
        if(registered) { context.unregisterReceiver(receiver);registered=false }
        if(workerDelegate.isInitialized()) {
            worker.queue.clear()
            worker.execute { disconnect() }
            worker.shutdown()
        }
    }
    companion object {
        fun isGamepad(device: InputDevice): Boolean = device.sources and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD ||
            device.sources and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
    }
}
