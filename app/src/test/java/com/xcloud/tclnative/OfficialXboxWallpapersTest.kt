package com.xcloud.tclnative

import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.settings.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OfficialXboxWallpapersTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() { app.getSharedPreferences("app_appearance",0).edit().clear().commit() }
    @Test fun publishedConsoleCatalogHasStableUniqueIdsAndOnlyOfficialStaticImageEndpoints() {
        val entries=OfficialXboxWallpapers.read(app)
        assertEquals(642,entries.size);assertEquals(entries.size,entries.distinctBy { it.id }.size)
        for(entry in entries) {
            assertTrue(entry.official);assertEquals(WallpaperCategory.OFFICIAL,entry.category)
            assertTrue(OfficialXboxWallpapers.trustedAsset(entry.artUrl!!))
            assertTrue(OfficialXboxWallpapers.trustedAsset(entry.previewUrl!!))
            assertEquals(entry,SeriesWallpaper.parse(entry.json()))
        }
        assertTrue(entries.map { it.name }.containsAll(listOf("XBOX Original Logo","XBOX 360 Logo","XBOX One Logo","XBOX Series Logo")))
        val first=entries.first()
        assertEquals(first.id,OfficialXboxWallpapers.entry(first.name,first.artUrl!!.substringBefore('?'))!!.id)
    }
    @Test fun officialOriginCannotBeForgedWithAnotherHostSchemeMediaOrIdentifier() {
        val official=OfficialXboxWallpapers.read(app).first();val url=official.artUrl!!
        for(bad in listOf(url.replace("assets.xboxservices.com","example.com"),url.replace("https:","http:"),
            url.replace("https://","https://assets.xboxservices.com@"),url.substringBefore('?').replace(".png",".mp4"),url+"#fragment")) {
            assertNull(OfficialXboxWallpapers.entry("Invalid",bad))
        }
        assertNull(OfficialXboxWallpapers.entry("Invalid",url,"https://example.com/preview.png"))
        assertNull(SeriesWallpaper.parse(JSONObject(official.json()).put("id","official:forged").toString()))
        val raw=app.assets.open("wallpapers/xbox-official.json").bufferedReader().use { it.readText() }
        try { OfficialXboxWallpapers.parse(raw.replace("static-image","native-animation"));fail("Animation claims must not be accepted") }
        catch(_: IllegalArgumentException) {}
    }
    @Test fun officialSelectionFavoritesAndFullCollectionRotationSurviveRestartWithoutTouchingGameSettings() {
        val catalog=OfficialXboxWallpapers.read(app);val selected=catalog.first()
        val playback=PlaybackSettingsStore(app).read();val library=GameLibrary(app,"official-wallpapers")
        library.toggleFavorite("GAME-FAVORITE")
        val store=SeriesPersonalizationStore(app);store.select(selected);store.toggleFavorite(selected)
        assertEquals(selected,SeriesPersonalizationStore(app).selected())
        assertEquals(listOf(selected),SeriesPersonalizationStore(app).favorites())
        assertTrue(library.isFavorite("GAME-FAVORITE"));assertFalse(library.isFavorite(selected.id))
        store.configureShuffle(WallpaperShuffle.HOURLY,WallpaperCategory.OFFICIAL,catalog)
        val serialized=app.getSharedPreferences("app_appearance",0).getStringSet("series_shuffle_items",emptySet())!!
        assertEquals(catalog.size,serialized.size)
        val active=store.resolve(1_700_000_000_000L,"one")!!
        assertTrue(active.official);assertTrue(active in catalog)
        val restored=SeriesPersonalizationStore(app)
        assertEquals(active,restored.resolve(1_700_000_000_001L,"two"))
        assertNotEquals(active,restored.resolve(1_700_003_600_000L,"two"))
        assertEquals(playback,PlaybackSettingsStore(app).read())
    }
}
