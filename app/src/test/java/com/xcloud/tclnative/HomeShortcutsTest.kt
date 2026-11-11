package com.xcloud.tclnative

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.xcloud.tclnative.home.*
import com.xcloud.tclnative.signaling.GameTitle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

class TestTvProvider : ContentProvider() {
    val channels=mutableMapOf<Long,ContentValues>()
    val programs=mutableMapOf<Long,ContentValues>()
    private var next=1L
    override fun onCreate()=true
    private fun rows(uri: Uri)=if(uri.pathSegments.first()=="channel") channels else programs
    override fun query(uri: Uri,projection: Array<out String>?,selection: String?,args: Array<out String>?,sort: String?): Cursor {
        val columns=projection!!.toList()
        return MatrixCursor(columns.toTypedArray()).apply {
            rows(uri).filter { (_,v) -> uri.getQueryParameter("channel")?.let { it==v.getAsString("channel_id") } ?: true }
                .forEach { (id,v) -> addRow(columns.map { if(it=="_id") id else v[it] }) }
        }
    }
    override fun insert(uri: Uri,values: ContentValues?): Uri {
        val id=next++; rows(uri)[id]=ContentValues(values)
        return ContentUris.withAppendedId(uri,id)
    }
    override fun update(uri: Uri,values: ContentValues?,where: String?,args: Array<out String>?): Int {
        rows(uri)[ContentUris.parseId(uri)] = ContentValues(values); return 1
    }
    override fun delete(uri: Uri,where: String?,args: Array<out String>?): Int {
        val id=ContentUris.parseId(uri)
        rows(uri).remove(id)
        if(uri.pathSegments.first()=="channel") programs.entries.removeAll { it.value.getAsLong("channel_id")==id }
        return 1
    }
    override fun openFile(uri: Uri,mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
        File(context!!.cacheDir,"tv-channel-logo.png"),ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
    override fun getType(uri: Uri)="vnd.android.cursor.item/test"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HomeShortcutsTest {
    @Test fun coldShortcutWaitsThroughLoginAndRecreationThenLaunchesOnlyTheAuthenticatedCatalogIdOnce() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val context=controller.get()
        val intent=GameLaunchLink.intent(context,"FORTNITE")
        val pending=PendingGameLaunch()
        assertTrue(pending.receive(intent))
        val restored=PendingGameLaunch(pending.titleId) // Login/recreation must keep the requested game.
        val game=GameTitle("FORTNITE","Fortnite")
        assertEquals(game,restored.consume(listOf(game)))
        assertNull(restored.consume(listOf(game))) // Back/cancel cannot relaunch the queue.
        assertTrue(restored.receive(intent))
        assertNull(restored.consume(listOf(GameTitle("OTHER","Other account catalog"))))
        assertNull(restored.titleId)
        controller.pause().stop().destroy()
    }
    @Test fun linksRejectServiceUrlsExtraPathsQueriesAndMissingCatalogIds() {
        listOf("https://example.com/FORTNITE","xcloud://other/FORTNITE","xcloud://play/FORTNITE/other",
            "xcloud://play/FORTNITE?token=secret","xcloud://play/FORTNITE#extra","xcloud://play/%2F..%2F",
            "xcloud://play/","xcloud://play/"+"a".repeat(129)).forEach {
            assertNull(it,GameLaunchLink.parse(Intent(Intent.ACTION_VIEW,Uri.parse(it))))
        }
    }
    @Test fun tvFavoritesCreateOneChannelWithDirectPlayTilesUpdateArtAndRemoveUnfavoritedGames() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val context=controller.get()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK,true)
        val provider=Robolectric.buildContentProvider(TestTvProvider::class.java).create().get()
        ShadowContentResolver.registerProviderInternal("android.media.tv",provider)
        val publisher=HomeShortcuts(context)
        publisher.enable(true)
        val first=GameTitle("FORTNITE","Fortnite",posterUrl="https://store-images.s-microsoft.com/fortnite")
        val second=GameTitle("HADES2","Hades II")
        val channel=publisher.sync(listOf(first,second))
        assertNotNull(channel); assertEquals(1,provider.channels.size); assertEquals(2,provider.programs.size)
        val tile=provider.programs.values.single { it.getAsString("title")=="Fortnite" }
        val parsed=Intent.parseUri(tile.getAsString(TvContract.PreviewPrograms.COLUMN_INTENT_URI),Intent.URI_INTENT_SCHEME)
        assertEquals("FORTNITE",GameLaunchLink.parse(parsed))
        assertFalse(parsed.toUri(0).contains("token"))
        publisher.sync(listOf(first.copy(posterUrl="https://store-images.s-microsoft.com/new-cover")))
        assertEquals(1,provider.channels.size); assertEquals(1,provider.programs.size)
        assertEquals("https://store-images.s-microsoft.com/new-cover",provider.programs.values.single().getAsString("poster_art_uri"))
        publisher.enable(false); publisher.sync(emptyList())
        assertTrue(provider.channels.isEmpty()); assertTrue(provider.programs.isEmpty())
        controller.pause().stop().destroy()
    }
}
