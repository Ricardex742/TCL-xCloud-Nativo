package com.xcloud.tclnative

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xcloud.tclnative.catalog.*
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.signaling.GameTitle
import com.xcloud.tclnative.touch.*
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageUiTest {
    private val settingsNames=listOf("Configurações","Settings","Ajustes","Paramètres","Einstellungen","Impostazioni","設定","설정","设置","Настройки","Ayarlar")
    @Before fun defaults() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_language",0).edit().clear().commit()
        UiText.apply(app,AppLanguage.PORTUGUESE);DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun reset() { ShadowDialog.getLatestDialog()?.dismiss();defaults() }
    private fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View) {
        (view as? GameDashboardView)?.awaitCatalog()
        repeat(2) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1280,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY))
            view.layout(0,0,1280,720);shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun capture(view: View,name: String) {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File("build/reports/languages/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Test fun portugueseIsTheDefaultAndAllTenNewCountriesPersistIndependentlyOfSessionSettings() {
        val app=RuntimeEnvironment.getApplication();val store=AppLanguageStore(app)
        val session=SessionOptionsStore(app,"language-test");val before=session.read()
        val playback=PlaybackSettingsStore(app).read();val systemLocale=java.util.Locale.getDefault()
        assertEquals(11,AppLanguage.entries.size);assertEquals(AppLanguage.PORTUGUESE,store.read())
        for((index,language) in AppLanguage.entries.withIndex()) {
            store.save(language);UiText.install(app)
            assertEquals(language,AppLanguageStore(app).read());assertEquals(language,UiText.language)
            assertEquals(settingsNames[index],UiText.text("Configurações"))
            assertEquals(before,session.read());assertEquals(playback,PlaybackSettingsStore(app).read())
            assertEquals(systemLocale,java.util.Locale.getDefault())
            assertEquals("de-DE",SessionOptions(locale="de-DE").normalized().locale)
        }
        app.getSharedPreferences("app_language",0).edit().putString("language","unknown").commit()
        UiText.install(app);assertEquals(AppLanguage.PORTUGUESE,UiText.language)
    }
    @Test fun everyCompiledPackContainsAllMessagesAndPreservesEveryInterpolationField() {
        val app=RuntimeEnvironment.getApplication()
        val source=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/ui_strings.xml"))
            .getElementsByTagName("string")
        val original=app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(AppLanguage.PORTUGUESE.locale) }).resources
        val fields=Regex("\\{[0-9]+\\}")
        assertTrue(source.length>700)
        for(language in AppLanguage.entries) {
            val resources=app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(language.locale) }).resources
            for(i in 0 until source.length) {
                val name=source.item(i).attributes.getNamedItem("name").nodeValue
                val id=resources.getIdentifier(name,"string",app.packageName)
                assertTrue("$language/$name missing",id!=0)
                val base=original.getString(id);val translated=resources.getString(id)
                assertEquals(name,UiText.resourceName(base))
                assertEquals("$language/$name fields",fields.findAll(base).map { it.value }.sorted().toList(),fields.findAll(translated).map { it.value }.sorted().toList())
                assertTrue(translated.isNotBlank());assertFalse(translated.contains("ZXQ"))
            }
        }
    }
    @Test fun existingViewsTranslateNestedLabelsAndReturnToPortugueseWithoutTouchingUserText() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        try {
            val root=FrameLayout(a);a.setContentView(root)
            val heading=LocalizedTextView(a).apply { text=UiText.text("Configurações") };root.addView(heading)
            val nested=LocalizedTextView(a).apply { text=UiText.text("Modo de vídeo: {0}",UiText.nested(VideoMode.ADAPTIVE.label)) };root.addView(nested)
            val raw=LocalizedTextView(a).apply { text="Voltar" };root.addView(raw)
            val rawTitle=LocalizedTextView(a).apply { text="Configurações" };root.addView(rawTitle)
            val composed=LocalizedTextView(a).apply { text=UiText.join(UiText.text("Configurações")," / ",UiText.text("Voltar")) };root.addView(composed)
            for((index,language) in AppLanguage.entries.withIndex()) {
                UiText.apply(a,language);UiText.refresh(root)
                assertEquals(settingsNames[index],heading.text.toString())
                assertEquals(UiText.text("Modo de vídeo: {0}",UiText.nested(VideoMode.ADAPTIVE.label)),nested.text.toString())
                assertEquals("Voltar",raw.text.toString());assertEquals("Configurações",rawTitle.text.toString())
                assertEquals(UiText.text("Configurações")+" / "+UiText.text("Voltar"),composed.text.toString())
                assertFalse(a.isDestroyed);assertSame(root,a.findViewById<ViewGroup>(android.R.id.content).getChildAt(0))
            }
            UiText.apply(a,AppLanguage.PORTUGUESE);UiText.refresh(root)
            assertEquals("Modo de vídeo: Adaptativo",nested.text.toString())
            assertEquals("Configurações / Voltar",composed.text.toString())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun choosingAndCancellingTheLanguageMenuKeepsTheThemeAndPlaybackPreferences() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        try {
            val root=FrameLayout(a);a.setContentView(root);layout(a.window.decorView)
            val heading=LocalizedTextView(a).apply { text=UiText.text("Configurações") };root.addView(heading)
            val before=PlaybackSettingsStore(a).read();AppearanceSettingsStore(a).save(UiTheme.PLATINUM_SILVER)
            var returns=0
            val menu=LanguageMenu.show(a) { returns++ }
            assertEquals(11,all(menu.body).filterIsInstance<TextView>().count { it.isClickable })
            menu.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,returns);assertEquals(AppLanguage.PORTUGUESE,AppLanguageStore(a).read())
            for(language in AppLanguage.entries) {
                val choice=LanguageMenu.show(a) { returns++ }
                all(choice.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains(language.nativeName) }.performClick()
                assertEquals(language,AppLanguageStore(a).read());assertEquals(language,UiText.language)
                assertEquals(before,PlaybackSettingsStore(a).read())
                assertEquals(UiTheme.PLATINUM_SILVER,AppearanceSettingsStore(a).read())
                assertEquals(UiText.text("Configurações"),heading.text.toString())
            }
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun theRealCatalogKeepsSelectionFavoritesAdapterAndAccountNamesAcrossAllLanguages() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val library=GameLibrary(a,"language-ui");val game=GameTitle("LANGUAGE-GAME","Configurações","LANGUAGE-PRODUCT")
        library.toggleFavorite(game.titleId)
        val view=GameDashboardView(a,library,{}, {}, {}, {})
        try {
            a.setContentView(view);view.setGames(listOf(game));view.selectTab(LibraryTab.ALL)
            view.setProfile(XboxProfile("Voltar","Configurações",subscription="ULTIMATE"))
            layout(view);view.restoreFocus();layout(view)
            val adapter=all(view).filterIsInstance<RecyclerView>().single().adapter
            for(language in AppLanguage.entries) {
                UiText.apply(a,language);UiText.refresh(view);layout(view)
                assertSame(adapter,all(view).filterIsInstance<RecyclerView>().single().adapter)
                assertEquals(game.titleId,view.selectedGame?.titleId);assertTrue(library.isFavorite(game.titleId))
                assertTrue(all(view).filterIsInstance<TextView>().any { it.text.toString()=="VOLTAR" })
                assertEquals("Configurações",view.selectedGame?.name)
                assertTrue(all(view).filterIsInstance<TextView>().any { it.text.toString()==LibraryTab.ALL.label })
            }
        } finally { view.close();controller.pause().stop().destroy() }
    }
    @Test fun translatingHudLabelsDoesNotChangeTheirCoordinatesButtonsOrGestures() {
        val app=RuntimeEnvironment.getApplication()
        fun bindings(preset: HudPreset)=GameHud.elements(preset).map { e ->
            e.copy(label="",gestures=e.gestures.mapValues { (_,g) -> g.copy(label="") })
        }
        val presets=listOf(HudPreset.EA_FC,HudPreset.CALL_OF_DUTY,HudPreset.FORZA,HudPreset.HADES)
        val before=presets.associateWith(::bindings)
        for(language in AppLanguage.entries) {
            UiText.apply(app,language)
            for(preset in presets) assertEquals(before[preset],bindings(preset))
            assertEquals(HudPreset.CALL_OF_DUTY,GameHud.automatic("test","Call of Duty: Modern Warfare III"))
        }
    }
    @Test fun englishAndJapaneseLoginViewsRenderAndLiveSwitchWithoutResettingTheLogin() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        val view=ConsoleLoginView(a,{}, {}, {})
        try {
            a.setContentView(view);layout(view)
            for(language in listOf(AppLanguage.ENGLISH,AppLanguage.GERMAN,AppLanguage.JAPANESE,AppLanguage.PORTUGUESE)) {
                UiText.apply(a,language);UiText.refresh(view);layout(view)
                val texts=all(view).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(texts.contains(UiText.text("ENTRAR COM QR CODE")))
                assertTrue(texts.contains(UiText.text("SEUS JOGOS.\nNA SUA TELA.")))
                capture(view,"login-${language.tag}")
            }
        } finally { view.close();controller.pause().stop().destroy() }
    }
}
