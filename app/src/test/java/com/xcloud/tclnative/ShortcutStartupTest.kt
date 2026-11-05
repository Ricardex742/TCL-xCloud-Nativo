package com.xcloud.tclnative

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.xcloud.tclnative.auth.*
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.home.GameLaunchLink
import com.xcloud.tclnative.ui.MainActivity
import com.xcloud.tclnative.ui.dashboard.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class TestShortcutActivity: MainActivity() {
    override fun createAuthenticator(): XalAuthenticator {
        val s=server!!
        return XalAuthenticator(PreferencesTokenStore(this),OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
            AuthEndpoints(s.url("/device").toString(),s.url("/token").toString(),s.url("/user").toString(),
                s.url("/xsts").toString(),s.url("/cloud").toString(),s.url("/transfer").toString(),s.url("/profile").toString()))
    }
    override fun createCatalogService(cache: CatalogCache?,beforeRequest: suspend () -> Unit)=
        CatalogService(baseUrl=server!!.url("/catalog").toString().trimEnd('/'),storeBaseUrl=server!!.url("/store").toString().trimEnd('/'),
            cache=cache,beforeRequest=beforeRequest)
    companion object { var server: MockWebServer?=null }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutStartupTest {
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun root(a: Activity)=a.findViewById<View>(android.R.id.content)
    private fun stopNativeTransportBeforeItStarts(activity: Activity) {
        // This integration test covers real auth/catalog/queue wiring, not JNI/WebRTC gameplay.
        val field=MainActivity::class.java.getDeclaredField("gameJob").apply { isAccessible=true }
        (field.get(activity) as? Job)?.cancel()
    }
    @Test fun savedSessionShortcutUsesItsCachedBannerBeforeAuthenticationAndKeepsItUntilTheCatalogIsDrawn() = shortcutFlow(true)
    @Test fun savedSessionShortcutResolvesOnlyItsOwnMissingArtworkAndCanReturnToTheCatalog() = shortcutFlow(false)
    private fun shortcutFlow(cached: Boolean) {
        val app=RuntimeEnvironment.getApplication()
        PreferencesTokenStore(app).clear()
        File(app.cacheDir,"catalog-art-v2.json").delete()
        PreferencesTokenStore(app).write(OAuthToken("t=test-access","test-refresh",System.currentTimeMillis()+3600_000,XalAuthenticator.XCLOUD_CLIENT_ID))
        val server=MockWebServer().apply { start() }; TestShortcutActivity.server=server
        val requests=ConcurrentLinkedQueue<RecordedRequest>()
        // Metadata still enforces HTTPS. Seed the HTTP image cache substitute here; actual
        // downloading/decoding and catalog card delivery are covered by DashboardArtworkTest.
        val artUrl="https://art.example/fortnite.png"
        val image=Bitmap.createBitmap(32,48,Bitmap.Config.RGB_565).apply { eraseColor(Color.BLUE) }
        val artwork=ArtworkBackend.get(app)
        artwork.cache.put(ArtworkRequest.create(artUrl,720,1080),image)
        artwork.cache.put(ArtworkRequest.create(artUrl,1920,1080),image)
        artwork.cache.put(ArtworkRequest.create(artUrl,1280,720),image)
        if(cached) CatalogCache(File(app.cacheDir,"catalog-art-v2.json")).save(listOf(
            com.xcloud.tclnative.signaling.GameTitle("FORTNITE","Fortnite","PRODUCT",posterUrl=artUrl,heroUrl=artUrl)))
        val releaseAuth=java.util.concurrent.CountDownLatch(1)
        server.dispatcher=object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse {
                requests.add(r)
                if(r.requestUrl!!.encodedPath=="/user") releaseAuth.await(8,TimeUnit.SECONDS)
                val body=when(r.requestUrl!!.encodedPath) {
                    "/user" -> """{"Token":"test-user","DisplayClaims":{"xui":[{"uhs":"test-hash"}]}}"""
                    "/xsts" -> """{"Token":"test-xsts"}"""
                    "/cloud" -> """{"gsToken":"test-cloud","offeringSettings":{"regions":[{"isDefault":true,"baseUri":"${server.url("/")}"}]}}"""
                    "/transfer" -> """{"access_token":"test-console"}"""
                    "/v2/titles" -> """{"results":[{"titleId":"FORTNITE","details":{"productTitle":"Fortnite","productId":"PRODUCT","supportedInputTypes":["NativeTouch"]}},{"titleId":"OTHER","details":{"productTitle":"Other","productId":"OTHER-PRODUCT"}}]}"""
                    "/store/v7.0/products" -> """{"Products":[{"StoreId":"PRODUCT","Image_Poster":{"URL":"$artUrl"}}]}"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        val intent=Intent(Intent.ACTION_VIEW,android.net.Uri.parse("xcloud://play/FORTNITE"))
        val controller=Robolectric.buildActivity(TestShortcutActivity::class.java,intent).create().start().resume().visible()
        val activity=controller.get()
        val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val releaseCleanup=CompletableDeferred<Unit>()
        try {
            shadowOf(activity.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN,true)
            assertFalse(all(root(activity)).any { it is ConsoleLoginView })
            val transition=all(root(activity)).filterIsInstance<SessionTransitionView>().single()
            if(cached) {
                val previewDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
                while(all(transition).filterIsInstance<ImageView>().none { it.contentDescription=="Banner de Fortnite" && it.drawable is BitmapDrawable } && System.nanoTime()<previewDeadline) {
                    shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS); Thread.sleep(5)
                }
                assertEquals(View.VISIBLE,transition.visibility)
                assertTrue(all(transition).filterIsInstance<ImageView>().any { it.contentDescription=="Banner de Fortnite" && it.drawable is BitmapDrawable })
                assertFalse(requests.any { it.requestUrl!!.encodedPath=="/v2/titles" })
            }
            releaseAuth.countDown()
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while(all(root(activity)).filterIsInstance<GameLoadingView>().none { it.visibility==View.VISIBLE } && System.nanoTime()<end) {
                shadowOf(Looper.getMainLooper()).runOneTask(); Thread.sleep(5)
            }
            stopNativeTransportBeforeItStarts(activity)
            val queue=all(root(activity)).filterIsInstance<GameLoadingView>().single()
            assertEquals(View.VISIBLE,queue.visibility)
            assertEquals(View.GONE,transition.visibility)
            assertFalse(all(root(activity)).any { it is GameDashboardView || it is ConsoleLoginView })
            val artDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while(all(queue).filterIsInstance<ImageView>().none { it.drawable is BitmapDrawable } && System.nanoTime()<artDeadline) {
                shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS); Thread.sleep(10)
            }
            assertTrue("The queue must receive artwork even though the catalog/grid was never built",all(queue).filterIsInstance<ImageView>().any { it.drawable is BitmapDrawable })
            val hydration=requests.filter { it.requestUrl!!.encodedPath=="/store/v7.0/products" }
            assertEquals(if(cached) 0 else 1,hydration.size)
            if(!cached) assertEquals("PRODUCT",hydration.single().requestUrl!!.queryParameter("bigIds"))
            assertFalse(requests.any { it.requestUrl!!.encodedPath in listOf("/device","/token","/profile") || it.requestUrl!!.encodedPath.contains("sigls") })
            assertNotNull(PreferencesTokenStore(app).read())
            // The deferred catalog is built when the user leaves the shortcut's queue,
            // without consuming the shortcut again or revalidating the same catalog.
            val closing=cleanupScope.launch(start=CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { withContext(NonCancellable) { releaseCleanup.await() } }
            }
            MainActivity::class.java.getDeclaredField("gameJob").apply { isAccessible=true }.set(activity,closing)
            activity.onKeyDown(KeyEvent.KEYCODE_BACK,KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK))
            assertEquals(View.VISIBLE,transition.visibility); assertTrue(transition.exiting)
            assertTrue(all(transition).filterIsInstance<TextView>().any { it.text.toString()=="Voltando ao catálogo…" })
            assertTrue(activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B)))
            shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)
            assertTrue(transition.exiting); assertFalse(all(root(activity)).any { it is GameDashboardView })
            releaseCleanup.complete(Unit)
            val menuDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while((all(root(activity)).none { it is GameDashboardView } || transition.visibility==View.VISIBLE) && System.nanoTime()<menuDeadline) {
                shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS); Thread.sleep(10)
            }
            assertEquals(1,all(root(activity)).filterIsInstance<GameDashboardView>().size)
            assertEquals(View.GONE,queue.visibility)
            assertEquals(View.GONE,transition.visibility)
            assertFalse(all(root(activity)).any { it is ConsoleLoginView })
            assertEquals(1,requests.count { it.requestUrl!!.encodedPath=="/v2/titles" })
        } finally { releaseAuth.countDown(); releaseCleanup.complete(Unit); cleanupScope.cancel(); controller.pause().stop().destroy(); server.shutdown(); TestShortcutActivity.server=null; PreferencesTokenStore(app).clear() }
    }
    @Test fun ordinarySavedSessionStartupShowsTheAppIconWithoutBuildingLoginOrLoadingGameArt() {
        val app=RuntimeEnvironment.getApplication()
        PreferencesTokenStore(app).write(OAuthToken("t=test-access","test-refresh",System.currentTimeMillis()+3600_000,XalAuthenticator.XCLOUD_CLIENT_ID))
        val release=java.util.concurrent.CountDownLatch(1)
        val server=MockWebServer().apply { dispatcher=object: Dispatcher() {
            override fun dispatch(r: RecordedRequest): MockResponse { release.await(8,TimeUnit.SECONDS); return MockResponse().setResponseCode(503) }
        }; start() }; TestShortcutActivity.server=server
        val c=Robolectric.buildActivity(TestShortcutActivity::class.java).create().start().resume().visible()
        try {
            assertEquals(android.media.AudioManager.STREAM_MUSIC,c.get().volumeControlStream)
            val transition=all(root(c.get())).filterIsInstance<SessionTransitionView>().single()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            val logo=all(transition).filterIsInstance<ImageView>().single { it.contentDescription=="Logo xCLOUD" }
            while(logo.drawable==null && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS); Thread.sleep(5) }
            assertEquals(View.VISIBLE,transition.visibility); assertEquals(View.VISIBLE,logo.visibility)
            assertNotNull(logo.drawable)
            assertTrue(logo.drawable.intrinsicWidth in 1..640)
            assertFalse(all(transition).filterIsInstance<ImageView>().any { it.contentDescription?.startsWith("Banner de")==true })
            assertFalse(all(root(c.get())).any { it is GameDashboardView || it is ConsoleLoginView })
        } finally { c.pause().stop().destroy(); release.countDown(); server.shutdown(); TestShortcutActivity.server=null; PreferencesTokenStore(app).clear() }
    }
    @Test fun shortcutWithoutSavedCredentialsStillRequiresLoginAndDoesNotCreateTheCatalog() {
        val app=RuntimeEnvironment.getApplication(); PreferencesTokenStore(app).clear()
        val server=MockWebServer().apply { start() }; TestShortcutActivity.server=server
        val c=Robolectric.buildActivity(TestShortcutActivity::class.java,GameLaunchLink.intent(app,"FORTNITE")).setup()
        try {
            assertTrue(all(root(c.get())).any { it is ConsoleLoginView })
            assertFalse(all(root(c.get())).any { it is GameDashboardView })
            assertEquals(0,server.requestCount)
        } finally { c.pause().stop().destroy(); server.shutdown(); TestShortcutActivity.server=null }
    }
}
