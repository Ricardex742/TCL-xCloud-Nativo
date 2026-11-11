package com.xcloud.tclnative

import android.content.Context
import android.os.Looper
import android.os.Vibrator
import com.xcloud.tclnative.webrtc.ControllerRumble
import com.xcloud.tclnative.webrtc.RumbleCommand
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class TouchRumbleTest {
    @Test fun phoneRumbleIsOptInForTouchPlayerZeroAndStopsWithTheSession() {
        val context=RuntimeEnvironment.getApplication();val vibrator=context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        shadowOf(vibrator).setHasVibrator(true)
        val rumble=ControllerRumble({null},phoneVibrator={vibrator})
        val command=RumbleCommand(0,60,20,0,0,250,0,0)
        rumble.play(command);shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(vibrator).isVibrating)
        rumble.touchRumble=true;rumble.play(command);shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shadowOf(vibrator).isVibrating)
        rumble.stop();assertFalse(shadowOf(vibrator).isVibrating)
        rumble.play(command.copy(player=1));shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(vibrator).isVibrating)
        rumble.enabled=false;rumble.play(command);shadowOf(Looper.getMainLooper()).idle()
        assertFalse(shadowOf(vibrator).isVibrating)
    }
}
