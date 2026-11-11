package com.xcloud.tclnative.settings

import com.xcloud.tclnative.i18n.UiText

import android.content.Context
import android.view.InputDevice
import com.xcloud.tclnative.webrtc.GamepadState
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

enum class StickCurve(private val sourceLabel: String,val exponent: Float) { LINEAR("Linear",1f), PRECISE("Mais precisão no centro",1.6f), FAST("Resposta rápida",.7f) ;
    val label: String get()=UiText.text(sourceLabel)
}
data class ControllerProfile(val leftDeadzone: Int=0,val rightDeadzone: Int=0,val leftSensitivity: Int=100,val rightSensitivity: Int=100,
    val curve: StickCurve=StickCurve.LINEAR,val invertLeftY: Boolean=false,val invertRightY: Boolean=false,
    val triggerDeadzone: Int=0,val swapTriggers: Boolean=false,val mapping: Map<Int,Int> = emptyMap()) {
    fun apply(state: GamepadState): GamepadState {
        if(leftDeadzone==0 && rightDeadzone==0 && leftSensitivity==100 && rightSensitivity==100 && curve==StickCurve.LINEAR &&
            !invertLeftY && !invertRightY && triggerDeadzone==0 && !swapTriggers && mapping.isEmpty()) return state
        fun stick(x: Float,y: Float,deadzone: Int,sensitivity: Int,invert: Boolean): Pair<Float,Float> {
            val safeX=if(x.isFinite()) x else 0f;val safeY=if(y.isFinite()) y else 0f
            if(deadzone==0 && sensitivity==100 && curve==StickCurve.LINEAR) return safeX.coerceIn(-1f,1f) to (safeY*(if(invert) -1 else 1)).coerceIn(-1f,1f)
            val magnitude=sqrt(safeX*safeX+safeY*safeY);val dead=deadzone.coerceIn(0,40)/100f
            if(magnitude<=dead || magnitude==0f) return 0f to 0f
            val value=((magnitude.coerceAtMost(1f)-dead)/(1f-dead)).pow(curve.exponent)*sensitivity.coerceIn(25,200)/100f
            return (safeX/magnitude*value).coerceIn(-1f,1f) to (safeY/magnitude*value*(if(invert) -1 else 1)).coerceIn(-1f,1f)
        }
        fun trigger(value: Float): Float {
            val dead=triggerDeadzone.coerceIn(0,40)/100f
            return if(!value.isFinite() || value<=dead) 0f else ((value-dead)/(1-dead)).coerceIn(0f,1f)
        }
        val left=stick(state.leftX,state.leftY,leftDeadzone,leftSensitivity,invertLeftY)
        val right=stick(state.rightX,state.rightY,rightDeadzone,rightSensitivity,invertRightY)
        var buttons=0
        for(bit in 0..15) { val source=1 shl bit;if(state.buttons and source!=0) buttons=buttons or (mapping[source] ?: source) }
        return state.copy(buttons=buttons,leftX=left.first,leftY=left.second,rightX=right.first,rightY=right.second,
            leftTrigger=trigger(if(swapTriggers) state.rightTrigger else state.leftTrigger),rightTrigger=trigger(if(swapTriggers) state.leftTrigger else state.rightTrigger))
    }
}
class ControllerProfileStore(context: Context) {
    private val prefs=context.getSharedPreferences("controller_profiles",Context.MODE_PRIVATE)
    private val cache=mutableMapOf<String,ControllerProfile>()
    fun key(device: InputDevice): String = "${device.vendorId}:${device.productId}:${device.descriptor}"
    fun read(key: String): ControllerProfile = cache.getOrPut(key) { runCatching {
        val obj=JSONObject(prefs.getString(key,"{}")!!)
        val mapping=obj.optJSONObject("mapping")
        ControllerProfile(obj.optInt("ld",0).coerceIn(0,40),obj.optInt("rd",0).coerceIn(0,40),
            obj.optInt("ls",100).coerceIn(25,200),obj.optInt("rs",100).coerceIn(25,200),
            StickCurve.entries.firstOrNull { it.name==obj.optString("curve") } ?: StickCurve.LINEAR,
            obj.optBoolean("il"),obj.optBoolean("ir"),obj.optInt("td",0).coerceIn(0,40),obj.optBoolean("st"),
            (0..15).mapNotNull { bit -> val source=1 shl bit;val target=mapping?.optInt(source.toString(),source) ?: source
                if(target!=source && (target==0 || (target in 1..32768 && target and (target-1)==0))) source to target else null }.toMap())
    }.getOrDefault(ControllerProfile()) }
    fun save(key: String,value: ControllerProfile) {
        cache[key]=value
        prefs.edit().putString(key,JSONObject().put("ld",value.leftDeadzone).put("rd",value.rightDeadzone)
            .put("ls",value.leftSensitivity).put("rs",value.rightSensitivity).put("curve",value.curve.name)
            .put("il",value.invertLeftY).put("ir",value.invertRightY).put("td",value.triggerDeadzone)
            .put("st",value.swapTriggers).put("mapping",JSONObject(value.mapping.mapKeys { it.key.toString() })).toString()).apply()
    }
    fun reset(key: String) { cache.remove(key);prefs.edit().remove(key).apply() }
}
