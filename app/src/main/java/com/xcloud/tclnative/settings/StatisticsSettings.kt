package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import org.json.JSONArray
import org.json.JSONObject

enum class StatisticField(private val source: String) {
    FPS("FPS do vídeo"), RTT("Ping (RTT)"), BITRATE("Bitrate"), RESOLUTION("Resolução recebida"), CODEC("Codec recebido"),
    DECODE("Tempo de decodificação"), JITTER("Jitter da rede"), LOSS("Perda de pacotes"), BUFFER("Buffer de vídeo"),
    DROPPED("Quadros descartados"), INPUT("Agendador de input");
    val label get()=UiText.text(source)
}
enum class StatisticsPosition(private val source: String) {
    TOP_LEFT("Superior esquerdo"), TOP_RIGHT("Superior direito"), BOTTOM_LEFT("Inferior esquerdo"), BOTTOM_RIGHT("Inferior direito");
    val label get()=UiText.text(source)
}
data class StatisticsSettings(val fields: Set<StatisticField> = DEFAULT_FIELDS,val detailed: Boolean=false,
                              val position: StatisticsPosition=StatisticsPosition.TOP_LEFT,val textSize: Int=12,val background: Int=70) {
    fun normalized()=copy(fields=fields.ifEmpty { DEFAULT_FIELDS },textSize=textSize.coerceIn(10,24),background=background.coerceIn(0,100))
    companion object { val DEFAULT_FIELDS=setOf(StatisticField.FPS,StatisticField.RTT,StatisticField.BITRATE) }
}
class StatisticsSettingsStore(context: Context) {
    private val prefs=context.getSharedPreferences("stream_statistics",Context.MODE_PRIVATE)
    fun read(): StatisticsSettings=try {
        val o=JSONObject(prefs.getString("layout",null) ?: "{}")
        val fields=o.optJSONArray("fields")?.let { values -> (0 until values.length()).mapNotNull { i ->
            StatisticField.entries.firstOrNull { it.name==values.optString(i) }
        }.toSet() } ?: StatisticsSettings.DEFAULT_FIELDS
        StatisticsSettings(fields,o.optBoolean("detailed"),StatisticsPosition.entries.firstOrNull { it.name==o.optString("position") }
            ?: StatisticsPosition.TOP_LEFT,o.optInt("size",12),o.optInt("background",70)).normalized()
    } catch(_: Exception) { StatisticsSettings() }
    fun save(value: StatisticsSettings) {
        val v=value.normalized()
        prefs.edit().putString("layout",JSONObject().put("fields",JSONArray(StatisticField.entries.filter { it in v.fields }.map { it.name }))
            .put("detailed",v.detailed).put("position",v.position.name).put("size",v.textSize).put("background",v.background).toString()).apply()
    }
}
