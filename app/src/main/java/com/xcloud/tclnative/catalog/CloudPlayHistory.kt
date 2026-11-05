package com.xcloud.tclnative.catalog

import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import java.util.Locale

/** Resolve the account's server order against the live catalog; history cannot grant access. */
object CloudPlayHistory {
    private fun key(value: String)=value.trim().uppercase(Locale.ROOT)
    fun resolve(catalog: List<GameTitle>,response: JSONObject): List<String> {
        val results=response.getJSONArray("results")
        val cloud=catalog.groupBy { key(it.titleId) }
        val products=catalog.filter { !it.productId.isNullOrBlank() }.groupBy { key(it.productId!!) }
        val xbox=catalog.filter { !it.xboxTitleId.isNullOrBlank() }.groupBy { key(it.xboxTitleId!!) }
        fun id(obj: JSONObject?,name: String)=obj?.optString(name)?.trim()?.takeIf { it.isNotEmpty() && it!="null" && it.length<=256 }
        val seen=mutableSetOf<String>(); val ordered=mutableListOf<String>()
        for(index in 0 until minOf(results.length(),100)) {
            val row=results.optJSONObject(index) ?: continue
            val details=row.optJSONObject("details")
            val game=id(row,"titleId")?.let { cloud[key(it)]?.singleOrNull() }
                ?: (id(details,"productId") ?: id(row,"productId"))?.let { products[key(it)]?.singleOrNull() }
                ?: (id(details,"xboxTitleId") ?: id(row,"xboxTitleId"))?.let { xbox[key(it)]?.singleOrNull() }
            if(game!=null && seen.add(key(game.titleId))) ordered+=game.titleId
            if(ordered.size==25) break
        }
        return ordered
    }
    fun select(catalog: List<GameTitle>,ids: List<String>): List<GameTitle> {
        val games=catalog.associateBy { key(it.titleId) }
        return ids.distinctBy(::key).mapNotNull { games[key(it)] }
    }
}
