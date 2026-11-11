package com.xcloud.tclnative.webrtc

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import com.xcloud.tclnative.settings.GyroMode

object GyroAim {
    fun active(mode: GyroMode,base: GamepadState)=when(mode) {
        GyroMode.OFF -> false;GyroMode.ALWAYS -> true;GyroMode.LT -> base.leftTrigger>.1f;GyroMode.LB -> base.buttons and 4096!=0
    }
    fun axes(x: Float,y: Float,rotation: Int,sensitivity: Int): Pair<Float,Float> {
        if(!x.isFinite() || !y.isFinite()) return 0f to 0f
        val gain=sensitivity.coerceIn(25,300)/400f
        val yaw=when(rotation) { Surface.ROTATION_90 -> -x;Surface.ROTATION_270 -> x;Surface.ROTATION_180 -> -y;else -> y }
        val pitch=when(rotation) { Surface.ROTATION_90 -> y;Surface.ROTATION_270 -> -y;Surface.ROTATION_180 -> -x;else -> x }
        return (yaw*gain).coerceIn(-1f,1f) to (pitch*gain).coerceIn(-1f,1f)
    }
}
class GyroController(context: Context,private val rotation: () -> Int,private val changed: () -> Unit): SensorEventListener {
    private val manager=context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor=manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val available get()=sensor!=null
    private var mode=GyroMode.OFF
    private var sensitivity=100
    private var axes=0f to 0f
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private val expire=Runnable { if(mode!=GyroMode.OFF) { axes=0f to 0f;changed() } }
    var status="Giroscópio: desligado"
        private set
    fun start(mode: GyroMode,sensitivity: Int,nativeTouch: Boolean,allowNativeTouch: Boolean=false) {
        stop();this.sensitivity=sensitivity
        if(mode==GyroMode.OFF) return
        if(nativeTouch && !allowNativeTouch) { status="Giroscópio: teste com toque nativo desativado";return }
        if(sensor==null) { status="Giroscópio: aparelho sem sensor";return }
        this.mode=mode
        val registered=manager?.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME)==true
        if(!registered) this.mode=GyroMode.OFF
        status=if(registered) "Giroscópio: ${mode.label}"+(if(nativeTouch) " • toque nativo experimental" else "") else "Giroscópio: registro recusado pelo Android"
    }
    fun apply(base: GamepadState): GamepadState = if(!GyroAim.active(mode,base)) base else base.copy(
        rightX=(base.rightX+axes.first).coerceIn(-1f,1f),rightY=(base.rightY+axes.second).coerceIn(-1f,1f))
    override fun onSensorChanged(event: SensorEvent) {
        if(mode==GyroMode.OFF) return
        axes=GyroAim.axes(event.values[0],event.values[1],rotation(),sensitivity);changed()
        handler.removeCallbacks(expire);handler.postDelayed(expire,100)
    }
    override fun onAccuracyChanged(sensor: Sensor?,accuracy: Int) { }
    fun stop() { manager?.unregisterListener(this);handler.removeCallbacks(expire);mode=GyroMode.OFF;axes=0f to 0f;status="Giroscópio: desligado" }
}
