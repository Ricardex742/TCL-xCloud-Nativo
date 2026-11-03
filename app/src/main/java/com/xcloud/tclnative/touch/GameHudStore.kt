package com.xcloud.tclnative.touch

import android.content.Context
import com.xcloud.tclnative.catalog.GameLibrary
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

/** Each title owns its layout on this device, independently of refreshable login grants. */
class GameHudStore(private val context: Context) {
    private fun action(data: JSONArray?): HudAction? {
        if(data==null) return null
        val keys=(0 until minOf(data.length(),HudBinding.entries.size)).mapNotNull { index ->
            HudBinding.entries.firstOrNull { it.name==data.optString(index) }
        }.toSet()
        return keys.takeIf { it.isNotEmpty() }?.let(::HudAction)
    }
    private fun encode(action: HudAction)=JSONArray(action.keys.map { it.name })
    private fun prefs(id: String)=context.getSharedPreferences("hud_"+GameLibrary.accountKey(id.uppercase(Locale.ROOT)),Context.MODE_PRIVATE)
    fun read(id: String): HudSettings { return try {
        val text=prefs(id).getString("layout",null) ?: return HudSettings()
        if(text.length>65536) return HudSettings()
        val data=JSONObject(text)
        val positions=data.optJSONObject("controls") ?: JSONObject()
        val overrides=positions.keys().asSequence().take(64).mapNotNull { key ->
            val item=positions.optJSONObject(key) ?: return@mapNotNull null
            val x=item.optDouble("x",Double.NaN).toFloat(); val y=item.optDouble("y",Double.NaN).toFloat()
            val size=item.optDouble("scale",1.0).toFloat()
            if(!x.isFinite() || !y.isFinite() || !size.isFinite()) return@mapNotNull null
            val gestures=item.optJSONObject("gestures")?.let { mapping ->
                HudSwipe.entries.mapNotNull gesture@ { direction ->
                    val saved=mapping.optJSONObject(direction.name) ?: return@gesture null
                    val input=action(saved.optJSONArray("keys")) ?: return@gesture null
                    direction to HudGesture(saved.optString("label",input.label).take(48),input)
                }.toMap()
            }
            key to HudPlacement(x.coerceIn(0f,1f),y.coerceIn(0f,1f),size.coerceIn(.55f,1.7f),
                HudBinding.entries.firstOrNull { it.name==item.optString("binding") },action(item.optJSONArray("keys")),gestures)
        }.toMap()
        HudSettings(HudPreset.entries.firstOrNull { it.name==data.optString("preset") },
            data.optInt("opacity",62).coerceIn(20,100),data.optInt("scale",100).coerceIn(75,150),
            data.optInt("sensitivity",100).coerceIn(40,1200),overrides,
            data.optInt("lookDeadzone",12).coerceIn(0,35),
            FootballScheme.entries.firstOrNull { it.name==data.optString("footballScheme") } ?: FootballScheme.CLASSIC)
    } catch(_: Exception) { HudSettings() } }
    fun save(id: String,settings: HudSettings) {
        val controls=JSONObject()
        settings.placements.forEach { (key,value) ->
            val control=JSONObject().put("x",value.x).put("y",value.y).put("scale",value.scale).put("binding",value.binding?.name)
            value.action?.let { control.put("keys",encode(it)) }
            value.gestures?.let { mappings ->
                val data=JSONObject()
                mappings.forEach { (direction,gesture) -> data.put(direction.name,JSONObject().put("label",gesture.label).put("keys",encode(gesture.action))) }
                control.put("gestures",data)
            }
            controls.put(key,control)
        }
        val data=JSONObject().put("version",2).put("preset",settings.preset?.name).put("opacity",settings.opacity)
            .put("scale",settings.scale).put("sensitivity",settings.sensitivity).put("controls",controls)
            .put("lookDeadzone",settings.lookDeadzone).put("footballScheme",settings.footballScheme.name)
        if(!prefs(id).edit().putString("layout",data.toString()).commit()) throw java.io.IOException("Não foi possível salvar o HUD neste aparelho.")
    }
}
