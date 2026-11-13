package com.xcloud.tclnative

import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.catalog.GameLibrary
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SeriesPersonalizationTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() { app.getSharedPreferences("app_appearance",0).edit().clear().commit();UiText.apply(app,AppLanguage.PORTUGUESE) }
    @Test fun allOptionsAreOptInAndWallpaperFavoritesDoNotChangeGameFavoritesOrPlayback() {
        val store=SeriesPersonalizationStore(app);val playback=PlaybackSettingsStore(app).read()
        val library=GameLibrary(app,"series-settings");library.toggleFavorite("MY-GAME")
        assertEquals(SeriesPreferences(),store.read());assertNull(store.resolve(1000,"opening"));assertNull(store.nextChange(1000))
        store.configureShuffle(WallpaperShuffle.OFF,WallpaperCategory.ALL,emptyList())
        assertFalse("Disabling an inactive shuffle must retain game-following backgrounds",store.read().gallery)
        store.save(store.read().copy(guide=SeriesGuideSide.RIGHT,libraryGrid=true,gamePassBadge=false,seriesBadge=false))
        val wallpaper=SeriesWallpaper("game:ART","Artwork",artUrl="https://example.com/hero.jpg",category=WallpaperCategory.GAMES)
        store.select(wallpaper);store.toggleFavorite(wallpaper)
        val restored=SeriesPersonalizationStore(app)
        assertEquals(wallpaper,restored.selected());assertTrue(restored.isFavorite(wallpaper.id))
        assertTrue(restored.read().libraryGrid);assertEquals(SeriesGuideSide.RIGHT,restored.read().guide)
        assertFalse(restored.read().gamePassBadge);assertFalse(restored.read().seriesBadge)
        assertTrue(library.isFavorite("MY-GAME"));assertFalse(library.isFavorite(wallpaper.id))
        assertEquals(playback,PlaybackSettingsStore(app).read())
        store.followGame();assertNull(restored.resolve(2000,"new"));assertTrue(store.isFavorite(wallpaper.id))
    }
    @Test fun periodicRotationPersistsDoesNotRepeatAndChangesOnlyWhenDue() {
        val now=1_700_000_000_000L
        for(mode in listOf(WallpaperShuffle.HOURLY,WallpaperShuffle.DAILY,WallpaperShuffle.WEEKLY)) {
            reset();val store=SeriesPersonalizationStore(app)
            val pool=SeriesWallpaper.builtIns.take(3);pool.forEach { store.toggleFavorite(it) }
            store.configureShuffle(mode,WallpaperCategory.FAVORITES,emptyList())
            val first=store.resolve(now,"one")!!
            assertTrue(first in pool);assertEquals(mode.periodMs,store.nextChange(now))
            val restarted=SeriesPersonalizationStore(app)
            assertEquals(first,restarted.resolve(now+mode.periodMs-1,"two"))
            val second=restarted.resolve(now+mode.periodMs,"two")!!
            assertNotEquals(first,second);assertTrue(second in pool)
            assertEquals(second,restarted.resolve(now+mode.periodMs+1,"three"))
            store.toggleFavorite(second)
            assertNotEquals(second,restarted.resolve(now+mode.periodMs+2,"three"))
        }
    }
    @Test fun openingShuffleIsStableWithinOneOpeningAndSingleOrEmptyPoolsHaveNoTimer() {
        val store=SeriesPersonalizationStore(app);val pool=SeriesWallpaper.builtIns.take(2)
        store.configureShuffle(WallpaperShuffle.OPEN,WallpaperCategory.XBOX,pool)
        val first=store.resolve(1000,"opening-one")
        assertEquals(first,store.resolve(2000,"opening-one"));assertNull(store.nextChange(2000))
        val second=store.resolve(3000,"opening-two")
        assertNotEquals(first,second)
        store.configureShuffle(WallpaperShuffle.OFF,WallpaperCategory.XBOX,pool)
        assertEquals("Disabling shuffle keeps its last displayed frame",second,store.selected())
        store.select(pool.first { it!=second });store.configureShuffle(WallpaperShuffle.OPEN,WallpaperCategory.XBOX,pool)
        assertEquals("A new manual pick must not reuse a stale shuffle frame",second,store.resolve(3500,"new-opening"))
        store.configureShuffle(WallpaperShuffle.HOURLY,WallpaperCategory.FAVORITES,emptyList())
        assertEquals(store.selected(),store.resolve(4000,"opening-two"));assertNull(store.nextChange(4000))
        store.toggleFavorite(pool[0]);assertEquals(pool[0],store.resolve(5000,"opening-two"));assertNull(store.nextChange(5000))
        store.select(pool[1]);assertEquals(WallpaperShuffle.OFF,store.shuffle());assertEquals(pool[1],store.resolve(6000,"opening-two"))
    }
    @Test fun corruptedSavedArtworkIsIgnoredAndClockRollbackRecovers() {
        val prefs=app.getSharedPreferences("app_appearance",0)
        prefs.edit().putString("series_wallpaper","broken JSON").putString("series_guide","unknown").commit()
        val store=SeriesPersonalizationStore(app)
        assertEquals(SeriesWallpaper.builtIns.first(),store.selected());assertEquals(SeriesGuideSide.OFF,store.read().guide)
        assertNull(SeriesWallpaper.parse("{\"id\":\"game:1\",\"name\":\"a\",\"url\":\"file:///private\"}"))
        store.configureShuffle(WallpaperShuffle.DAILY,WallpaperCategory.ALL,SeriesWallpaper.builtIns)
        val first=store.resolve(1_700_000_000_000L,"one")
        assertNotEquals(first,store.resolve(1_600_000_000_000L,"one"))
        assertEquals(WallpaperShuffle.DAILY.periodMs,store.nextChange(1_600_000_000_000L))
    }
    @Test fun artworkChoicesAreBoundedAndKeepTheSelectedGameAheadOfTheCatalogLimit() {
        val games=(0 until 200).map { com.xcloud.tclnative.signaling.GameTitle("GAME-$it","Game $it",heroUrl="https://example.com/$it.jpg") }
        val choices=com.xcloud.tclnative.ui.dashboard.SeriesWallpaperGallery.fromGames(games,listOf(games.last()))
        assertEquals(SeriesPersonalizationStore.MAX_ITEMS,choices.size)
        assertEquals("game:GAME-199",choices.first().id);assertEquals(choices.size,choices.distinctBy { it.id }.size)
    }
}
