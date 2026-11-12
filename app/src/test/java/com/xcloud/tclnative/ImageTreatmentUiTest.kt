package com.xcloud.tclnative

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.StreamResolution
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageTreatmentUiTest {
    @Test fun reconstructionActivationAndSameFrameComparisonPreserveStreamAndControllerSettings() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            val first=panel.showUpscaling();capture(first,"adaptive-reconstruction-options")
            assertEquals(original,store.read())
            click(first,"Método:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Reconstrução xCloud")
            assertEquals(FsrMode.RECONSTRUCTION,store.read().fsr);assertEquals(FsrOutput.AUTO,store.read().fsrOutput)
            assertEquals(original.resolution,store.read().resolution);assertEquals(original.touchMode,store.read().touchMode)
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Qualidade do processamento")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Qualidade")
            assertEquals(ReconstructionQuality.QUALITY,store.read().reconstruction.quality)
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Comparação:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"Dividida")
            assertEquals(ImageComparison.SPLIT,store.read().reconstruction.comparison)
            assertEquals(original.videoCodec,store.read().videoCodec)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { c.pause().stop().destroy() }
    }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun click(dialog: ConsoleDialog,text: String) = all(dialog.body).filterIsInstance<TextView>()
        .single { it.isClickable && it.text.contains(text) }.performClick()
    private fun capture(dialog: ConsoleDialog,name: String) {
        val view=dialog.window!!.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(dialog.window!!.attributes.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.AT_MOST))
        view.layout(0,0,view.measuredWidth,view.measuredHeight)
        assertTrue(view.measuredHeight<=648)
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
        File("build/reports/dashboard/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Test fun treatmentAndCodecMenusKeepTheOriginalStreamAndControllerChoices() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            activity.getSharedPreferences("stream_preferences",0).edit().clear().commit()
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val updates=mutableListOf<PlaybackSettings>();val panel=PlaybackSettingsPanel(activity,store,{ updates+=it },{},{},{})
            val treatment=panel.showTreatment();capture(treatment,"image-treatment-options")
            assertEquals(original,store.read());assertTrue(updates.isEmpty())
            click(treatment,"Perfil natural")
            assertEquals(ImageSharpening.RCAS_LUMA,store.read().imageTreatment.sharpening)
            assertEquals(25,store.read().imageTreatment.deband);assertEquals(25,store.read().sharpness)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val scale=panel.chooseFsr();click(scale,"SGSR 1")
            assertEquals(FsrMode.SGSR1,store.read().fsr)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val codec=panel.chooseCodec();capture(codec,"h264-main-options");click(codec,"H.264 Main")
            assertEquals(VideoCodecMode.H264_MAIN,store.read().videoCodec)
            assertEquals(StreamResolution.HD_1080_HQ,store.read().resolution)
            assertEquals(original.touchMode,store.read().touchMode)
            assertEquals(25,store.read().imageTreatment.deband)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            val image=panel.showUpscaling();click(image,"Restaurar imagem original")
            assertFalse(store.read().imageTreatment.active);assertEquals(FsrMode.OFF,store.read().fsr)
            assertEquals(VideoCodecMode.H264_MAIN,store.read().videoCodec)
            (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun eachMethodShowsOnlyItsOwnControlsAndChildMenusReturnToTheSessionCaller() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity=c.get().apply { setContentView(FrameLayout(this)) }
            val store=PlaybackSettingsStore(activity);val original=store.read()
            val panel=PlaybackSettingsPanel(activity,store,{},{},{},{})
            val image=panel.showImage()
            val rows=all(image.body).filterIsInstance<TextView>().filter { it.isClickable }
            assertEquals(2,rows.size);assertTrue(rows.any { it.text.contains("Upscaling e tratamento:") })
            image.dismiss()
            for(mode in FsrMode.entries) {
                val settings=original.copy(fsr=mode,videoMode=VideoMode.LOW_LATENCY);store.save(settings)
                val dialog=panel.showUpscaling();capture(dialog,"organized-image-${mode.name.lowercase()}")
                val names=all(dialog.body).filterIsInstance<TextView>().filter { it.isClickable }.map { it.text.toString() }
                assertEquals(settings,store.read())
                assertEquals(mode!=FsrMode.OFF,names.any { it.contains("Saída do escalador:") })
                assertEquals(mode==FsrMode.OFF,names.any { it.contains("Upscaling nativo:") })
                assertEquals(mode==FsrMode.OFF || mode==FsrMode.FSR1,names.any { it.contains("Método de nitidez:") })
                assertEquals(mode==FsrMode.RECONSTRUCTION,names.any { it.contains("Comparação:") })
                assertEquals(mode!=FsrMode.RECONSTRUCTION,names.any { it.startsWith("Nitidez:") })
                assertTrue(names.any { it.contains("Filtros adicionais:") });dialog.dismiss()
            }
            store.save(original)
            var back=0
            val session=panel.showUpscaling(onBack={back++});click(session,"Método:")
            click(ShadowDialog.getLatestDialog() as ConsoleDialog,"SGSR 1")
            (ShadowDialog.getLatestDialog() as ConsoleDialog).cancel()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(1,back)
            store.save(original.copy(videoMode=VideoMode.COMPATIBILITY,fsr=FsrMode.RECONSTRUCTION))
            val compatibility=panel.showUpscaling()
            assertFalse(all(compatibility.body).filterIsInstance<TextView>().any { it.isClickable && it.text.contains("Intensidade") })
            compatibility.dismiss()
        } finally { c.pause().stop().destroy() }
    }
    @Test fun filterPresetsAdjustTheActiveMethodsIntensityWithoutChangingInactiveControls() {
        val c=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val store=PlaybackSettingsStore(c.get());val panel=PlaybackSettingsPanel(c.get(),store,{},{},{},{})
            for(mode in FsrMode.entries) {
                val before=PlaybackSettings(fsr=mode,sharpness=0,fsrSharpness=75,
                    reconstruction=ReconstructionSettings(strength=100,comparison=ImageComparison.ORIGINAL))
                store.save(before);click(panel.showTreatment(),"Perfil leve")
                val after=store.read()
                assertEquals(before.fsr,after.fsr);assertEquals(before.resolution,after.resolution)
                assertEquals(before.touchMode,after.touchMode);assertEquals(before.videoCodec,after.videoCodec)
                assertEquals(if(mode==FsrMode.OFF) 25 else before.sharpness,after.sharpness)
                assertEquals(if(mode==FsrMode.FSR1 || mode==FsrMode.SGSR1) 25 else before.fsrSharpness,after.fsrSharpness)
                assertEquals(if(mode==FsrMode.RECONSTRUCTION) 25 else before.reconstruction.strength,after.reconstruction.strength)
                if(mode==FsrMode.SGSR1 || mode==FsrMode.RECONSTRUCTION) assertEquals(before.imageTreatment.sharpening,after.imageTreatment.sharpening)
                (ShadowDialog.getLatestDialog() as ConsoleDialog).dismiss()
            }
        } finally { c.pause().stop().destroy() }
    }
}
