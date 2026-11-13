package com.xcloud.tclnative.settings

import android.content.Context
import com.xcloud.tclnative.i18n.UiText
import org.json.JSONObject

enum class SeriesGuideSide(private val source: String) {
    OFF("Menu central — padrão"), LEFT("Esquerda"), RIGHT("Direita");
    val label get()=UiText.text(source)
}
enum class WallpaperShuffle(private val source: String,val periodMs: Long=0) {
    OFF("Sem troca automática"), OPEN("Ao abrir o aplicativo"),
    HOURLY("A cada hora",3_600_000), DAILY("Diariamente",86_400_000), WEEKLY("Semanalmente",604_800_000);
    val label get()=UiText.text(source)
}
enum class WallpaperCategory(private val source: String) {
    ALL("Todos os fundos"), OFFICIAL("Oficiais Xbox"), RECREATIONS("Recriações"), XBOX("Do aplicativo"), ABSTRACT("Abstratos"), GAMES("Jogos"), FAVORITES("Favoritos");
    val label get()=UiText.text(source)
}
enum class WallpaperStyle { FLOW, HORIZON, AURORA, ORBIT, SUNSET, PLATINUM }

/** Public artwork metadata only. A wallpaper never represents ownership or changes a cloud title. */
data class SeriesWallpaper(val id: String,val name: String,val style: WallpaperStyle=WallpaperStyle.FLOW,
                           val artUrl: String?=null,val category: WallpaperCategory=WallpaperCategory.ABSTRACT,
                           val previewUrl: String?=null) {
    val official get()=category==WallpaperCategory.OFFICIAL && id.startsWith("official:")
    val recreation get()=RecreatedXboxBackgrounds.find(id) ?: RecreatedXboxBackgrounds.artworkDesign(this)
    val label get()=if(recreation!=null) UiText.text("Recriação • {0}",UiText.nested(name)) else if(artUrl==null) UiText.text(name) else name
    fun json(): String=JSONObject().put("id",id).put("name",name).put("style",style.name)
        .put("url",artUrl).put("category",category.name).put("preview",previewUrl).toString()
    companion object {
        val builtIns=listOf(
            SeriesWallpaper("flow","Fluxo Xbox",WallpaperStyle.FLOW,category=WallpaperCategory.XBOX),
            SeriesWallpaper("horizon","Horizonte Xbox",WallpaperStyle.HORIZON,category=WallpaperCategory.XBOX),
            SeriesWallpaper("aurora","Aurora",WallpaperStyle.AURORA),
            SeriesWallpaper("orbit","Órbita",WallpaperStyle.ORBIT),
            SeriesWallpaper("sunset","Pôr do sol",WallpaperStyle.SUNSET),
            SeriesWallpaper("platinum","Platina",WallpaperStyle.PLATINUM))
        fun parse(raw: String?): SeriesWallpaper? = try {
            val o=JSONObject(raw ?: "")
            val id=o.getString("id");val name=o.getString("name")
            val builtIn=builtIns.firstOrNull { it.id==id }
            if(builtIn!=null) builtIn else if(id.startsWith("recreation-art:")) {
                val candidate=SeriesWallpaper(id,name,artUrl=o.optString("url"),category=WallpaperCategory.RECREATIONS,
                    previewUrl=o.optString("preview").takeIf { it.isNotBlank() })
                val original=RecreatedXboxBackgrounds.originalArtwork(candidate)
                val effect=RecreatedXboxBackgrounds.artworkEffect(candidate)
                if(original!=null && effect!=null) RecreatedXboxBackgrounds.animatedArtwork(original,effect) else null
            } else if(id.startsWith("recreation:")) {
                val url=o.optString("url").takeIf { it.isNotBlank() && OfficialXboxWallpapers.trustedAsset(it) }
                RecreatedXboxBackgrounds.find(id)?.wallpaper(url)
            } else {
                val url=o.optString("url")
                if(id.startsWith("official:")) OfficialXboxWallpapers.entry(name,url,o.optString("preview").takeIf { it.isNotBlank() })?.takeIf { it.id==id }
                else if(!id.startsWith("game:") || id.length>180 || name.isBlank() || name.length>240 ||
                    url.length>4096 || !(url.startsWith("https://") || url.startsWith("http://"))) null
                else SeriesWallpaper(id,name,artUrl=url,category=WallpaperCategory.GAMES)
            }
        } catch(_: Exception) { null }
    }
}

data class SeriesPreferences(val gallery: Boolean=false,val guide: SeriesGuideSide=SeriesGuideSide.OFF,
                             val libraryGrid: Boolean=false,val gamePassBadge: Boolean=true,val seriesBadge: Boolean=true)

/** Separate device preferences; no playback, account or entitlement settings are written here. */
class SeriesPersonalizationStore(context: Context) {
    private val prefs=context.getSharedPreferences("app_appearance",Context.MODE_PRIVATE)
    private var poolSnapshot: Set<String>?=null
    private var decodedPool=emptyList<SeriesWallpaper>()
    fun read()=SeriesPreferences(prefs.getBoolean("series_gallery",false),
        SeriesGuideSide.entries.firstOrNull { it.name==prefs.getString("series_guide",null) } ?: SeriesGuideSide.OFF,
        prefs.getBoolean("series_library_grid",false),prefs.getBoolean("series_badge_pass",true),prefs.getBoolean("series_badge_xs",true))
    fun save(value: SeriesPreferences) { prefs.edit().putBoolean("series_gallery",value.gallery).putString("series_guide",value.guide.name)
        .putBoolean("series_library_grid",value.libraryGrid).putBoolean("series_badge_pass",value.gamePassBadge)
        .putBoolean("series_badge_xs",value.seriesBadge).apply() }
    fun selected()=SeriesWallpaper.parse(prefs.getString("series_wallpaper",null)) ?: SeriesWallpaper.builtIns.first()
    fun select(value: SeriesWallpaper) {
        prefs.edit().putBoolean("series_gallery",true).putString("series_wallpaper",value.json())
            .putString("series_shuffle",WallpaperShuffle.OFF.name).remove("series_rotation_slot")
            .remove("series_rotation_item").remove("series_rotation_time").apply()
    }
    fun followGame() { prefs.edit().putBoolean("series_gallery",false).apply() }
    fun favorites(): List<SeriesWallpaper> = decode(prefs.getStringSet("series_wallpaper_favorites",emptySet()) ?: emptySet())
    fun isFavorite(id: String)=favorites().any { it.id==id }
    fun toggleFavorite(value: SeriesWallpaper): Boolean {
        val all=favorites().toMutableList();val exists=all.removeAll { it.id==value.id }
        if(!exists && all.size<MAX_ITEMS) all.add(value)
        prefs.edit().putStringSet("series_wallpaper_favorites",all.map { it.json() }.toSet()).apply()
        return !exists && all.any { it.id==value.id }
    }
    fun shuffle()=WallpaperShuffle.entries.firstOrNull { it.name==prefs.getString("series_shuffle",null) } ?: WallpaperShuffle.OFF
    fun category()=WallpaperCategory.entries.firstOrNull { it.name==prefs.getString("series_shuffle_category",null) } ?: WallpaperCategory.FAVORITES
    fun configureShuffle(mode: WallpaperShuffle,category: WallpaperCategory,items: List<SeriesWallpaper>) {
        val wasGallery=read().gallery
        val current=if(wasGallery && shuffle()!=WallpaperShuffle.OFF) SeriesWallpaper.parse(prefs.getString("series_rotation_item",null)) else null
        val editor=prefs.edit().putBoolean("series_gallery",wasGallery || mode!=WallpaperShuffle.OFF).putString("series_shuffle",mode.name)
            .putString("series_shuffle_category",category.name)
            .putStringSet("series_shuffle_items",items.distinctBy { it.id }.take(MAX_ROTATION_ITEMS).map { it.json() }.toSet())
            .remove("series_rotation_slot")
        if(mode==WallpaperShuffle.OFF) {
            current?.let { editor.putString("series_wallpaper",it.json()) }
            editor.remove("series_rotation_item").remove("series_rotation_time")
        } else if(!wasGallery) editor.remove("series_rotation_item").remove("series_rotation_time")
        editor.apply()
    }
    private fun decode(items: Set<String>,limit: Int=MAX_ITEMS)=items.asSequence().take(limit).mapNotNull(SeriesWallpaper::parse).distinctBy { it.id }.sortedBy { it.id }.toList()
    private fun pool(): List<SeriesWallpaper> {
        if(category()==WallpaperCategory.FAVORITES) return favorites()
        val raw=prefs.getStringSet("series_shuffle_items",emptySet()) ?: emptySet()
        if(raw!==poolSnapshot) { decodedPool=decode(raw,MAX_ROTATION_ITEMS);poolSnapshot=raw }
        return decodedPool
    }

    /** Resolve once per opening/period, persist across process death, and avoid immediate repeats. */
    @Synchronized fun resolve(now: Long,opening: String): SeriesWallpaper? {
        if(!read().gallery) return null
        val mode=shuffle();if(mode==WallpaperShuffle.OFF) return selected()
        val pool=pool();if(pool.isEmpty()) return selected()
        val slot=if(mode==WallpaperShuffle.OPEN) "open:$opening" else "time"
        val active=SeriesWallpaper.parse(prefs.getString("series_rotation_item",null))
        val last=prefs.getLong("series_rotation_time",0L)
        val changed=prefs.getString("series_rotation_slot",null)!=slot || active==null || pool.none { it.id==active.id } ||
            (mode.periodMs>0 && (now<last || now-last>=mode.periodMs))
        if(!changed) return pool.first { it.id==active!!.id }
        val candidates=pool.filter { it.id!=(active?.id ?: selected().id) }.ifEmpty { pool }
        val next=candidates.random()
        prefs.edit().putString("series_rotation_item",next.json()).putString("series_rotation_slot",slot)
            .putLong("series_rotation_time",now).apply()
        return next
    }
    fun nextChange(now: Long): Long? {
        val period=shuffle().periodMs
        if(!read().gallery || period==0L || pool().size<2) return null
        return (prefs.getLong("series_rotation_time",now)+period-now).coerceIn(1000L,period)
    }
    companion object { const val MAX_ITEMS=80;const val MAX_ROTATION_ITEMS=2048 }
}
