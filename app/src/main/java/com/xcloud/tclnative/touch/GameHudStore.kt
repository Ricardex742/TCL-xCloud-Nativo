package com.xcloud.tclnative.touch

import android.content.Context
import com.xcloud.tclnative.catalog.GameLibrary
import org.json.JSONObject
import java.util.Locale

/** Each title owns its layout on this device, independently of refreshable login grants. */
class GameHudStore(private val context: Context) {
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
            key to HudPlacement(x.coerceIn(0f,1f),y.coerceIn(0f,1f),size.coerceIn(.55f,1.7f),
                HudBinding.entries.firstOrNull { it.name==item.optString("binding") })
        }.toMap()
        HudSettings(HudPreset.entries.firstOrNull { it.name==data.optString("preset") },
            data.optInt("opacity",62).coerceIn(20,100),data.optInt("scale",100).coerceIn(75,150),
            data.optInt("sensitivity",100).coerceIn(40,220),overrides)
    } catch(_: Exception) { HudSettings() } }
    fun save(id: String,settings: HudSettings) {
        val controls=JSONObject()
        settings.placements.forEach { (key,value) -> controls.put(key,JSONObject().put("x",value.x).put("y",value.y)
            .put("scale",value.scale).put("binding",value.binding?.name)) }
        val data=JSONObject().put("version",1).put("preset",settings.preset?.name).put("opacity",settings.opacity)
            .put("scale",settings.scale).put("sensitivity",settings.sensitivity).put("controls",controls)
        if(!prefs(id).edit().putString("layout",data.toString()).commit()) throw java.io.IOException("Não foi possível salvar o HUD neste aparelho.")
    }
}
