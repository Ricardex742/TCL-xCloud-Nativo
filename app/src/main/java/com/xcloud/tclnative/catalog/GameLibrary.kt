package com.xcloud.tclnative.catalog

import android.content.Context
import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

enum class LibraryTab(val label: String) {
    RECENT("VOLTAR A JOGAR"), FAVORITES("FAVORITOS"), GAME_PASS("GAME PASS"),
    ADDED("ADIÇÕES RECENTES"), ALL("TODOS OS JOGOS")
}

data class PlayRecord(val lastPlayed: Long, val launches: Int)

/** Cloud access programs (including GPULTIMATE) also cover BYOG; only the official gallery proves membership. */
object GamePassCatalog {
    fun productKey(id: String) = id.trim().uppercase(Locale.ROOT)
    fun contains(game: GameTitle, normalizedProducts: Set<String>, resolvedTitles: Set<String> = emptySet()): Boolean =
        !game.freeToPlay && (game.productId?.let { productKey(it) in normalizedProducts } == true ||
            productKey(game.titleId) in resolvedTitles)
}

/** Only a rendered first frame counts as played; a cancelled cloud queue does not. */
class GameLibrary(private val context: Context, accountId: String, legacyAccountId: String? = null) {
    private var prefs = preferences(accountId).let { old ->
        old.getString("migrated_to", null)?.takeIf { it.matches(Regex("xuid:[0-9]{1,20}")) }
            ?.let(::preferences) ?: old
    }
    init { if (legacyAccountId != null && legacyAccountId != accountId) migrate(preferences(legacyAccountId), accountId) }
    private fun preferences(account: String) = context.getSharedPreferences("library_" + accountKey(account), Context.MODE_PRIVATE)
    /** A verified XUID replaces a relying-party hash without discarding this account's old library. */
    @Synchronized fun bindXboxAccount(xuid: String) {
        require(xuid.matches(Regex("[0-9]{1,20}")))
        val account = "xuid:$xuid"
        val previous = prefs
        prefs = preferences(account)
        if (previous !== prefs) migrate(previous, account)
    }
    private fun migrate(old: android.content.SharedPreferences, account: String) {
        val owner = old.getString("migrated_to", null)
        if (owner != null) return // Never resurrect a removed favorite on the next login.
        val merged = favorites + (old.getStringSet("favorites", emptySet()) ?: emptySet())
        val plays = runCatching { JSONObject(prefs.getString("plays", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val legacy = runCatching { JSONObject(old.getString("plays", "{}") ?: "{}") }.getOrDefault(JSONObject())
        legacy.keys().forEach { id -> if (!plays.has(id)) plays.put(id, legacy.get(id)) }
        val editor=prefs.edit().putStringSet("favorites",merged).putString("plays",plays.toString())
        if(old.contains("cloud_recent") && (!prefs.contains("cloud_recent") || old.getLong("cloud_recent_at",0)>prefs.getLong("cloud_recent_at",0)))
            editor.putString("cloud_recent",old.getString("cloud_recent",null)).putLong("cloud_recent_at",old.getLong("cloud_recent_at",0))
        check(editor.commit())
        check(old.edit().putString("migrated_to", account).commit())
    }
    val favorites: Set<String> @Synchronized get() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    @Synchronized fun isFavorite(id: String) = favorites.any { it.equals(id, true) }
    @Synchronized fun toggleFavorite(id: String): Boolean {
        val next = favorites.toMutableSet()
        val existing = next.firstOrNull { it.equals(id, true) }
        val added = if (existing != null) { next.remove(existing); false } else { next.add(id); true }
        // Complete the disk write before acknowledging the star, including immediate app exits.
        if (!prefs.edit().putStringSet("favorites", next).commit()) throw java.io.IOException("Não foi possível salvar os favoritos no aparelho.")
        return added
    }
    fun recordPlayed(id: String, now: Long = System.currentTimeMillis()) {
        val data = runCatching { JSONObject(prefs.getString("plays", "{}")!!) }.getOrDefault(JSONObject())
        val old = data.optJSONObject(id)?.optInt("launches") ?: 0
        data.put(id, JSONObject().put("lastPlayed", now).put("launches", old + 1))
        prefs.edit().putString("plays", data.toString()).apply()
    }
    fun records(): Map<String, PlayRecord> {
        val data = runCatching { JSONObject(prefs.getString("plays", "{}")!!) }.getOrDefault(JSONObject())
        return data.keys().asSequence().mapNotNull { id -> data.optJSONObject(id)?.let {
            id to PlayRecord(it.optLong("lastPlayed"), it.optInt("launches"))
        } }.toMap()
    }
    @Synchronized fun cloudRecent(): List<String>? = runCatching {
        val text=prefs.getString("cloud_recent",null) ?: return null
        if(text.length>16384) return null
        val array=org.json.JSONArray(text)
        (0 until minOf(array.length(),25)).mapNotNull { array.optString(it)?.takeIf { id -> id.isNotBlank() && id!="null" && id.length<=256 } }
    }.getOrNull()
    @Synchronized fun saveCloudRecent(ids: List<String>) {
        val data=ids.filter { it.isNotBlank() && it.length<=256 }.distinctBy { it.uppercase(Locale.ROOT) }.take(25)
        if(!prefs.edit().putString("cloud_recent",org.json.JSONArray(data).toString()).putLong("cloud_recent_at",System.currentTimeMillis()).commit())
            throw java.io.IOException("Não foi possível salvar o histórico do Xbox neste aparelho.")
    }
    companion object {
        fun accountKey(account: String): String = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        @Suppress("UNUSED_PARAMETER")
        fun select(games: List<GameTitle>, tab: LibraryTab, favorites: Set<String>, records: Map<String, PlayRecord>,
                   gamePass: List<String>?, added: List<String>?, query: String = "",
                   gamePassTitles: Set<String> = emptySet(),cloudRecent: List<String>? = null): List<GameTitle> {
            val selected = when (tab) {
                LibraryTab.ALL -> games
                LibraryTab.FAVORITES -> games.filter { game -> favorites.any { it.equals(game.titleId, true) } }
                LibraryTab.RECENT -> CloudPlayHistory.select(games,cloudRecent ?: emptyList())
                LibraryTab.GAME_PASS -> {
                    val products=(gamePass ?: emptyList()).map(GamePassCatalog::productKey).toSet()
                    games.filter { GamePassCatalog.contains(it,products,gamePassTitles) }.sortedBy { it.name.lowercase(Locale.ROOT) }
                }
                LibraryTab.ADDED -> ordered(games, added ?: emptyList())
            }
            return selected.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        }
        private fun ordered(games: List<GameTitle>, products: List<String>): List<GameTitle> {
            val index = products.map(GamePassCatalog::productKey).withIndex().associate { it.value to it.index }
            return games.filter { it.productId?.let(GamePassCatalog::productKey) in index }
                .sortedBy { index[it.productId?.let(GamePassCatalog::productKey)] }
        }
    }
}
