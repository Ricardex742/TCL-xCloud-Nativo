package com.xcloud.tclnative

import android.app.Activity
import android.content.Context
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.view.*
import android.widget.TextView
import com.xcloud.tclnative.i18n.*
import com.xcloud.tclnative.settings.*
import com.xcloud.tclnative.ui.dashboard.*
import com.xcloud.tclnative.webrtc.AudioPlaybackPolicy
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowDialog
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], qualifiers="w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AudioPlaybackPolicyTest {
    private val app get()=RuntimeEnvironment.getApplication()
    @Before fun reset() {
        app.getSharedPreferences("stream_preferences",0).edit().clear().commit()
        UiText.apply(app,AppLanguage.PORTUGUESE);DashboardTheme.apply(app,UiTheme.GREEN)
    }
    @After fun cleanup() { (ShadowDialog.getLatestDialog() as? ConsoleDialog)?.dismiss() }
    @Test fun realLibraryBuilderGetsTheOptionalFlagWithoutChangingStereoMediaAttributesOrSystemMode() {
        val manager=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val modeBefore=manager.mode;val volumeBefore=manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Inspect the pinned dependency's actual configuration, without creating a JNI peer.
        fun field(b: JavaAudioDeviceModule.Builder,name: String)=b.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(b)
        for ((mode,sdk,expected) in listOf(Triple(AudioPlaybackMode.STANDARD,28,false),
            Triple(AudioPlaybackMode.LOW_LATENCY,28,true),Triple(AudioPlaybackMode.LOW_LATENCY,24,false))) {
            val builder=AudioPlaybackPolicy.configure(JavaAudioDeviceModule.builder(app),mode,sdk)
            assertEquals(expected,field(builder,"useLowLatency"));assertEquals(true,field(builder,"useStereoOutput"))
            val attrs=field(builder,"audioAttributes") as AudioAttributes
            assertEquals(AudioAttributes.USAGE_GAME,attrs.usage);assertEquals(AudioAttributes.CONTENT_TYPE_MOVIE,attrs.contentType)
            assertEquals(AudioManager.STREAM_MUSIC,attrs.volumeControlStream)
        }
        assertEquals(modeBefore,manager.mode);assertEquals(volumeBefore,manager.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertTrue(AudioPlaybackPolicy.diagnostics(AudioPlaybackMode.LOW_LATENCY).contains("Modo efetivo: não exposto"))
        assertTrue(AudioPlaybackPolicy.diagnostics(AudioPlaybackMode.LOW_LATENCY,24).contains("indisponível nesta API"))
    }
    @Test fun defaultAndUnknownProfilesUseStandardAndTheSelectionSurvivesAllVideoModes() {
        val store=PlaybackSettingsStore(app);assertEquals(AudioPlaybackMode.STANDARD,store.read().audioMode)
        for (video in VideoMode.entries) {
            val selected=PlaybackSettings(videoMode=video,audioMode=AudioPlaybackMode.LOW_LATENCY,audioGain=125)
            store.save(selected);assertEquals(selected,PlaybackSettingsStore(app).read())
            assertEquals(AudioPlaybackMode.LOW_LATENCY,selected.forSession().audioMode)
        }
        app.getSharedPreferences("stream_preferences",0).edit().putString("audio_mode","removed").commit()
        assertEquals(AudioPlaybackMode.STANDARD,store.read().audioMode)
        assertEquals(125,store.read().audioGain)
    }
    @Test fun audioMenuSelectsTheProfileForTheNextSessionAndLeavesTheOtherSettingsAlone() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
        try {
            val store=PlaybackSettingsStore(a);val before=store.read();var changed: PlaybackSettings?=null
            val panel=PlaybackSettingsPanel(a,store,{changed=it},{},{},{})
            val audio=panel.showAudio()
            all(audio.panel).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("Perfil de áudio",true) }.performClick()
            val picker=ShadowDialog.getLatestDialog() as ConsoleDialog
            val root=picker.window!!.decorView;val width=picker.window!!.attributes.width
            repeat(2) { root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(648,View.MeasureSpec.EXACTLY));root.layout(0,0,width,648);shadowOf(Looper.getMainLooper()).idle() }
            val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
            File("build/reports/dashboard/audio-playback-profile.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            val low=all(picker.body).filterIsInstance<TextView>().single { it.isClickable && it.text.contains("Baixa latência",true) }
            assertTrue(low.requestFocus());picker.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A))
            assertEquals(before.copy(audioMode=AudioPlaybackMode.LOW_LATENCY),store.read())
            assertEquals(store.read(),changed)
        } finally { controller.pause().stop().destroy() }
    }
    private fun all(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
}
