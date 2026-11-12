package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.xcloud.tclnative.auth.DeviceCodeInfo
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.webrtc.StreamResolution
import com.xcloud.tclnative.ui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConsoleUiTest {
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun layout(view: View,w: Int,h: Int) {
        repeat(2) { index ->
            view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY))
            view.layout(0,0,w,h)
            if (index==0) shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun save(view: View,name: String) {
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        File("build/reports/dashboard/$name.png").apply { parentFile?.mkdirs() }.outputStream()
            .use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }
    private fun render(dialog: ConsoleDialog,name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val root=dialog.window!!.decorView
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
            root.layout(0,0,root.measuredWidth,root.measuredHeight)
            if (it==0) shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue(dialog.panel.height <= 648)
        save(root,name)
    }
    @Test fun searchSupportsKeyboardSubmissionClearAndBackWithoutWhitePlatformControls() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        val results=mutableListOf<String>()
        val search=ConsoleDialogs.search(activity,"Forza") { results.add(it) }
        val field=all(search.panel).filterIsInstance<EditText>().single()
        assertFalse(field.defaultFocusHighlightEnabled)
        field.setText("Fortnite")
        render(search,"green-search")
        field.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        assertEquals(listOf("Fortnite"),results); assertFalse(search.isShowing)
        val clear=ConsoleDialogs.search(activity,"Old") { results.add(it) }
        all(clear.panel).filterIsInstance<TextView>().single { it.text.toString()=="LIMPAR BUSCA" }.performClick()
        assertEquals(listOf("Fortnite",""),results)
        val back=ConsoleDialogs.search(activity,"Keep") { results.add(it) }
        back.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
        assertFalse(back.isShowing); assertEquals(2,results.size)
        controller.pause().stop().destroy()
    }
    @Test fun settingsKeepAccountActionsAndExplicitChoicesReachableWithTheController() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
        val store=PlaybackSettingsStore(activity)
        val updates=mutableListOf<PlaybackSettings>()
        var managed=0; var refreshed=0; var loggedOut=0
        val panel=PlaybackSettingsPanel(activity,store,{ updates.add(it) },{ managed++ },{ refreshed++ },{ loggedOut++ })
        val dialog=panel.show()
        render(dialog,"green-settings")
        assertFalse(all(dialog.body).filterIsInstance<TextView>().any { it.text.contains("Rewards") || it.text.contains("Pesquisar") })
        fun choose(dialog: ConsoleDialog, text: String) {
            val item=all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(text) }
            assertFalse(item.defaultFocusHighlightEnabled)
            assertTrue(item.requestFocus())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BUTTON_A))
        }
        choose(dialog,"Vídeo e transmissão")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Modo de vídeo:")
        val video=ShadowDialog.getLatestDialog() as ConsoleDialog
        render(video,"green-video-choice")
        assertTrue(updates.isEmpty()); assertEquals(VideoMode.LOW_LATENCY,store.read().videoMode)
        choose(video,"Adaptativo")
        assertEquals(VideoMode.ADAPTIVE,store.read().videoMode)
        val reopened=ShadowDialog.getLatestDialog() as ConsoleDialog
        choose(reopened,"Resolução:")
        val resolution=ShadowDialog.getLatestDialog() as ConsoleDialog
        render(resolution,"green-resolution-choice")
        assertEquals(4,all(resolution.body).filterIsInstance<TextView>().count { it.isClickable })
        choose(resolution,"720p")
        assertEquals(StreamResolution.HD_720,store.read().resolution)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        val imageCategory=panel.showImage()
        render(imageCategory,"image-settings-0.32.0")
        choose(imageCategory,"Upscaling e tratamento:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Upscaling nativo:")
        val upscale=ShadowDialog.getLatestDialog() as ConsoleDialog
        render(upscale,"green-upscaling-choice")
        choose(upscale,"Desativado —")
        assertFalse(store.read().nativeUpscaling); assertEquals(3,updates.size)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
        shadowOf(Looper.getMainLooper()).idle()
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Proporção:")
        val aspect=ShadowDialog.getLatestDialog() as ConsoleDialog
        render(aspect,"green-aspect-choice")
        choose(aspect,"18:9")
        assertEquals(VideoAspect.RATIO_18_9,store.read().aspect)
        val parent=ShadowDialog.getLatestDialog() as ConsoleDialog
        choose(parent,"Proporção:")
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4,updates.size)
        val back=ShadowDialog.getLatestDialog() as ConsoleDialog
        assertTrue(back.isShowing)
        back.dismiss()
        choose(panel.showAccount(),"Gerenciar assinatura"); assertEquals(1,managed)
        choose(panel.showAccount(),"Atualizar catálogo"); assertEquals(1,refreshed)
        val last=panel.showAccount()
        choose(last,"Trocar conta")
        assertEquals(1,loggedOut); assertFalse(last.isShowing)
        controller.pause().stop().destroy()
    }
    @Test fun themedMessagePreservesTheVideoRecoveryCancelCallback() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        var cancelled=0; var recovered=0
        val dialog=ConsoleDialogs.message(controller.get(),"Imagem sem atualização","A sessão continua aberta.",
            listOf(ConsoleAction("Recuperar vídeo") { recovered++ }), onCancel={ cancelled++ })
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,cancelled); assertEquals(0,recovered)
        controller.pause().stop().destroy()
    }
    @Test fun compatibilitySelectionPersistsTheLighterPresetAndTheGameMenuHasAnExplicitDisconnect() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        val store=PlaybackSettingsStore(activity)
        store.save(PlaybackSettings(sharpness=75,saturation=150,videoCodec=VideoCodecMode.H264_BASELINE,fsr=FsrMode.FSR1))
        val panel=PlaybackSettingsPanel(activity,store,{}, {}, {}, {})
        val choices=panel.chooseVideo()
        render(choices,"compatibility-choice")
        all(choices.body).filterIsInstance<TextView>().single { it.text.contains("Compatibilidade —") }.performClick()
        assertEquals(VideoMode.COMPATIBILITY,store.read().videoMode)
        assertEquals(VideoCodecMode.H264,store.read().videoCodec)
        assertEquals(FsrMode.OFF,store.read().fsr)
        assertEquals(StreamResolution.HD_720,store.read().resolution)
        assertTrue(store.read().nativeUpscaling)
        assertEquals(0,store.read().sharpness); assertEquals(100,store.read().saturation)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        var stats=0; var disconnected=0; var hudOpened=0
        val game=GameMenuPanel.show(activity,{}, {stats++}, {}, {disconnected++},hud={hudOpened++},aspect={})
        render(game,"game-menu")
        all(game.body).filterIsInstance<TextView>().single { it.text.contains("HUD e controles") }.performClick()
        assertEquals(1,hudOpened)
        val statistics=GameMenuPanel.show(activity,{}, {stats++}, {}, {disconnected++},hud={hudOpened++})
        all(statistics.body).filterIsInstance<TextView>().single { it.text.contains("estatísticas") }.performClick()
        assertEquals(1,stats); assertEquals(0,disconnected)
        val leaving=GameMenuPanel.show(activity,{}, {}, {}, {disconnected++})
        all(leaving.body).filterIsInstance<TextView>().single { it.text.contains("Desconectar sessão") }.performClick()
        assertEquals(1,disconnected)
        controller.pause().stop().destroy()
    }
    @Test fun codecChoicesRemainOptInAndHardwareExperimentsAreLabeled() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        val store=PlaybackSettingsStore(activity)
        val original=PlaybackSettings(resolution=StreamResolution.HD_720,sharpness=25,touchMode=TouchMode.NATIVE)
        store.save(original)
        val updates=mutableListOf<PlaybackSettings>()
        val panel=PlaybackSettingsPanel(activity,store,{updates.add(it)},{},{},{})
        val dialog=panel.chooseCodec()
        render(dialog,"light-codec-choice")
        assertEquals(original,store.read());assertTrue(updates.isEmpty())
        all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("H.264 leve") }.performClick()
        assertEquals(original.copy(videoCodec=VideoCodecMode.H264_BASELINE),store.read())
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        val standard=panel.chooseCodec()
        val choices=all(standard.body).filterIsInstance<TextView>().filter { it.isClickable }
        for(name in listOf("H.264 High","H.265/HEVC Main","VP9","AV1")) {
            assertTrue(choices.single { it.text.contains(name) }.text.contains("experimental"))
        }
        assertEquals(original.copy(videoCodec=VideoCodecMode.H264_BASELINE),store.read())
        assertEquals(1,updates.size)
        all(standard.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("H.264 — padrão") }.performClick()
        assertEquals(original,store.read())
        assertEquals(2,updates.size)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        controller.pause().stop().destroy()
    }
    @Test fun imageAndControllerChoicesPersistWithoutCyclingValuesWhenOpeningTheirMenus() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
        val store=PlaybackSettingsStore(activity)
        var tested=0
        val panel=PlaybackSettingsPanel(activity,store,{}, {}, {}, {}, { tested++ })
        fun choose(dialog: ConsoleDialog,text: String) {
            all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.toString().contains(text) }.performClick()
        }
        val image=panel.showUpscaling()
        render(image,"green-image-adjustments")
        assertEquals(0,store.read().sharpness)
        choose(image,"Nitidez:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"25%")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Cores da imagem")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Saturação:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"125%")
        assertEquals(25,store.read().sharpness); assertEquals(125,store.read().saturation)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        val controls=panel.showControls()
        render(controls,"green-controller-settings")
        choose(controls,"Controle físico e coop")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Coop local:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Ativado —")
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.showRumble(),"Vibração:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Desativada")
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.showControls(),"Jogar com toque:")
        choose(ShadowDialog.getLatestDialog() as ConsoleDialog,"Exclusivo — controle virtual")
        assertTrue(store.read().localCoOp); assertFalse(store.read().rumble)
        assertEquals(TouchMode.VIRTUAL,store.read().touchMode)
        (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        choose(panel.showRumble(),"Testar vibração")
        assertEquals(1,tested)
        controller.pause().stop().destroy()
    }
    @Test fun fsrIsAnExplicitOptInAndRcasStrengthCanBeChangedWithoutChangingTheStreamProfile() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=controller.get().apply { setContentView(FrameLayout(this)) }
            layout(activity.window.decorView,1280,720)
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity)
            val panel=PlaybackSettingsPanel(activity,store,{}, {}, {}, {})
            val choices=panel.chooseFsr();render(choices,"amd-fsr-choice")
            assertEquals(FsrMode.OFF,store.read().fsr)
            all(choices.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("AMD FSR 1.0.2") }.performClick()
            assertEquals(FsrMode.FSR1,store.read().fsr)
            assertEquals(StreamResolution.HD_1080_HQ,store.read().resolution)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val image=panel.showUpscaling();render(image,"amd-fsr-image-adjustments")
            all(image.body).filterIsInstance<TextView>().single { it.isClickable && it.text.startsWith("Nitidez:") }.performClick()
            val strength=ShadowDialog.getLatestDialog() as ConsoleDialog
            all(strength.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("75%") }.performClick()
            assertEquals(75,store.read().fsrSharpness);assertEquals(0,store.read().sharpness)
            assertEquals(FsrMode.FSR1,store.read().fsr)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun fsrOutputOffersThreeExplicitChoicesAndSavingKeepsStreamAndNativeScalingPreferences() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=controller.get().apply { setContentView(FrameLayout(this)) }
            layout(activity.window.decorView,1280,720)
            val store=PlaybackSettingsStore(activity);val original=PlaybackSettings()
            store.save(original)
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            val choices=panel.chooseFsrOutput();render(choices,"fsr-output-0.31.8")
            val rows=all(choices.body).filterIsInstance<TextView>().filter { it.isClickable }
            assertEquals(3,rows.size);assertEquals(original,store.read())
            rows.single { it.text.contains("4K —") }.performClick()
            assertEquals(original.copy(fsrOutput=FsrOutput.UHD_4K),store.read())
            assertFalse(all((ShadowDialog.getLatestDialog() as ConsoleDialog).body).filterIsInstance<TextView>().any { it.text.contains("Saída do escalador:") })
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val again=panel.chooseFsrOutput()
            all(again.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("1440p —") }.performClick()
            assertEquals(original.copy(fsrOutput=FsrOutput.QHD_1440),store.read())
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun qhdIsSelectableWithAControllerAndReopensWithoutEnablingLocalUpscalingFilters() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get().apply { setContentView(FrameLayout(this)) }
        layout(activity.window.decorView,1280,720)
        val store=PlaybackSettingsStore(activity)
        val before=PlaybackSettings(fsr=FsrMode.OFF)
        store.save(before)
        val panel=PlaybackSettingsPanel(activity,store,{}, {}, {}, {})
        try {
            val dialog=panel.chooseResolution()
            render(dialog,"resolution-qhd-options")
            val qhd=all(dialog.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("1440p") }
            assertTrue(qhd.requestFocus())
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
            assertEquals(before.copy(resolution=StreamResolution.QHD_1440),PlaybackSettingsStore(activity).read())
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val reopened=panel.chooseResolution()
            val selected=all(reopened.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("1440p") }
            assertTrue(selected.isSelected)
            render(reopened,"resolution-qhd-selected")
            reopened.dismiss()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun loginAndQrUseTheNewThemeAndTheRenderedQrStillScans() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        assertEquals("xCLOUD",activity.packageManager.getApplicationLabel(activity.applicationInfo))
        assertNotEquals(0,activity.applicationInfo.icon)
        assertNotEquals(0,activity.applicationInfo.banner)
        var signIns=0; var backs=0
        val view=ConsoleLoginView(activity,{ signIns++ },{ backs++ },{})
        activity.setContentView(view)
        layout(view,1280,720)
        save(view,"green-login-1280x720")
        view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(1,signIns)
        view.showConnecting("Conectando sua conta…")
        view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Connecting must not start another login",1,signIns)
        view.beginQr()
        val uri="https://microsoft.com/link?otc=DEMO1234"
        val matrix=QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,420,420)
        val qr=Bitmap.createBitmap(420,420,Bitmap.Config.ARGB_8888)
        for (x in 0 until 420) for (y in 0 until 420) qr.setPixel(x,y,if(matrix[x,y]) Color.BLACK else Color.WHITE)
        view.showQr(DeviceCodeInfo("DEMO1234","fake-device", "https://microsoft.com/link",uri,5,
            System.currentTimeMillis()+600_000,qr))
        layout(view,1280,720)
        val capture=Bitmap.createBitmap(1280,720,Bitmap.Config.ARGB_8888)
        view.draw(Canvas(capture))
        val pixels=IntArray(1280*720); capture.getPixels(pixels,0,1280,0,0,1280,720)
        val result=MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(1280,720,pixels))))
        assertEquals(uri,result.text)
        capture.recycle()
        save(view,"green-qr-1280x720")
        assertTrue(all(view).filter { it.isFocusable && it.isClickable }.all { !it.defaultFocusHighlightEnabled })
        view.handleKey(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_B)); assertEquals(1,backs)
        for ((w,h) in listOf(1280 to 590,1920 to 1080,3840 to 2160)) {
            org.robolectric.shadows.ShadowDisplayManager.changeDisplay(0,"w${w}dp-h${h}dp-land-mdpi")
            shadowOf(Looper.getMainLooper()).idle()
            view.landing(); layout(view,w,h); save(view,"green-login-${w}x$h")
            view.beginQr(); view.showQr(DeviceCodeInfo("DEMO1234","fake-device","https://microsoft.com/link",uri,5,
                System.currentTimeMillis()+600_000,qr))
            layout(view,w,h); save(view,"green-qr-${w}x$h")
        }
        view.close(); controller.pause().stop().destroy(); qr.recycle()
    }
}
