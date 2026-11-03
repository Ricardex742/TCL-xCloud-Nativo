package com.xcloud.tclnative.catalog

import android.content.Context
import com.xcloud.tclnative.signaling.GameTitle
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

enum class LibraryTab(val label: String) {
    RECENT("JOGADOS RECENTEMENTE"), FAVORITES("FAVORITOS"), GAME_PASS("GAME PASS"),
    ADDED("ADIÇÕES RECENTES"), ALL("TODOS OS JOGOS")
}

data class PlayRecord(val lastPlayed: Long, val launches: Int)

/** A title's catalog program is membership evidence; its user's tier is a separate claim. */
object GamePassCatalog {
    fun productKey(id: String) = id.trim().uppercase(Locale.ROOT)
    fun contains(game: GameTitle, normalizedProducts: Set<String>): Boolean = !game.freeToPlay && (
        XboxProfile.subscription(game.programs + game.userPrograms)?.let { it != "FREE" } == true ||
            game.productId?.let { productKey(it) in normalizedProducts } == true)
}

/** Only a rendered first frame counts as played; a cancelled cloud queue does not. */
class GameLibrary(context: Context, accountId: String) {
    private val prefs = context.getSharedPreferences("library_" + accountKey(accountId), Context.MODE_PRIVATE)
    val favorites: Set<String> get() = prefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun toggleFavorite(id: String): Boolean {
        val next = favorites.toMutableSet()
        val added = if (id in next) { next.remove(id); false } else { next.add(id); true }
        prefs.edit().putStringSet("favorites", next).apply()
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
    companion object {
        fun accountKey(account: String): String = MessageDigest.getInstance("SHA-256")
            .digest(account.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

        fun select(games: List<GameTitle>, tab: LibraryTab, favorites: Set<String>, records: Map<String, PlayRecord>,
                   gamePass: List<String>?, added: List<String>?, query: String = ""): List<GameTitle> {
            val selected = when (tab) {
                LibraryTab.ALL -> games
                LibraryTab.FAVORITES -> games.filter { it.titleId in favorites }
                LibraryTab.RECENT -> games.filter { it.titleId in records }
                    .sortedByDescending { records[it.titleId]?.lastPlayed }
                LibraryTab.GAME_PASS -> {
                    val products=(gamePass ?: emptyList()).map(GamePassCatalog::productKey).toSet()
                    games.filter { GamePassCatalog.contains(it,products) }.sortedBy { it.name.lowercase(Locale.ROOT) }
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
