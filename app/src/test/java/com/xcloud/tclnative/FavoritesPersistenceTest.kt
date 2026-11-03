package com.xcloud.tclnative

import android.app.Activity
import com.xcloud.tclnative.catalog.GameLibrary
import com.xcloud.tclnative.catalog.LibraryTab
import com.xcloud.tclnative.signaling.GameTitle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FavoritesPersistenceTest {
    @Test fun favoriteIsOnDiskBeforeExitAndSurvivesAccountHashRotationAndActivityRecreation() {
        val first=Robolectric.buildActivity(Activity::class.java).setup()
        val library=GameLibrary(first.get(),"xuid:123","first-grant")
        listOf("007FIRSTLIGHT","1000XRESIST","ASSASSINSCREEDODYSSEY").forEach { assertTrue(library.toggleFavorite(it)) }
        // Read the actual XML, not SharedPreferences' process-local memory cache.
        val file=File(first.get().applicationInfo.dataDir,"shared_prefs/library_${GameLibrary.accountKey("xuid:123")}.xml")
        val xml=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val saved=(xml.getElementsByTagName("set").item(0) as org.w3c.dom.Element).getElementsByTagName("string")
        assertEquals(setOf("007FIRSTLIGHT","1000XRESIST","ASSASSINSCREEDODYSSEY"),
            (0 until saved.length).map { saved.item(it).textContent }.toSet())
        first.pause().stop().destroy()
        val second=Robolectric.buildActivity(Activity::class.java).setup()
        val restored=GameLibrary(second.get(),"xuid:123","refreshed-grant")
        assertEquals(3,restored.favorites.size)
        assertTrue(restored.isFavorite("007firstlight"))
        assertTrue(GameLibrary(second.get(),"xuid:456","another-user").favorites.isEmpty())
        second.pause().stop().destroy()
    }
    @Test fun migrationPreservesExistingFavoritesButNeverResurrectsARemovedGame() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val context=controller.get()
        GameLibrary(context,"legacy").apply { toggleFavorite("FORTNITE"); recordPlayed("FORTNITE",100) }
        val migrated=GameLibrary(context,"xuid:789","legacy")
        assertTrue(migrated.isFavorite("FORTNITE")); assertEquals(1,migrated.records()["FORTNITE"]?.launches)
        assertFalse(migrated.toggleFavorite("fortnite"))
        assertTrue(GameLibrary(context,"xuid:789","legacy").favorites.isEmpty())
        assertTrue(GameLibrary(context,"legacy").favorites.isEmpty()) // Fallback grant follows its verified owner.
        assertTrue(GameLibrary(context,"xuid:999","legacy").favorites.isEmpty()) // No cross-account replay.
        controller.pause().stop().destroy()
    }
    @Test fun profileIdentityBindsLegacyFavoritesAndCatalogMatchingIgnoresIdCase() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val library=GameLibrary(controller.get(),"without-xsts-xid")
        library.toggleFavorite("fortnite")
        library.bindXboxAccount("1111")
        val recreated=GameLibrary(controller.get(),"xuid:1111","new-grant")
        val game=GameTitle("FORTNITE","Fortnite")
        assertEquals(listOf(game),GameLibrary.select(listOf(game),LibraryTab.FAVORITES,recreated.favorites,emptyMap(),null,null))
        assertFalse(recreated.toggleFavorite("FORTNITE"))
        assertTrue(GameLibrary(controller.get(),"xuid:1111").favorites.isEmpty())
        controller.pause().stop().destroy()
    }
}
