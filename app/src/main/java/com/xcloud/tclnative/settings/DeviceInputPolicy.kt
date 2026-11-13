package com.xcloud.tclnative.settings

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.InputDevice
import com.xcloud.tclnative.i18n.UiText
import com.xcloud.tclnative.signaling.GameTitle

/** Generic box firmware can advertise a touchscreen without exposing any direct-touch input. */
data class InputDeviceProfile(val television: Boolean,val touchscreen: Boolean,
                              val configuredTouchscreen: Boolean = touchscreen,
                              val directTouchInput: Boolean = touchscreen) {
    val touchDevice: Boolean get()=!television && touchscreen && configuredTouchscreen && directTouchInput
    fun diagnostics(): String {
        fun yes(value: Boolean)=if(value) UiText.text("sim") else UiText.text("não")
        return UiText.text("Detecção de toque: TV {0} | anunciado {1} | configurado {2} | entrada detectada {3}",
            UiText.nested(yes(television)),UiText.nested(yes(touchscreen)),
            UiText.nested(yes(configuredTouchscreen)),UiText.nested(yes(directTouchInput)))
    }
    companion object {
        fun read(context: Context): InputDeviceProfile {
            val pm=context.packageManager
            val mode=(context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            return InputDeviceProfile(mode==Configuration.UI_MODE_TYPE_TELEVISION ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION),
                pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
                context.resources.configuration.touchscreen==Configuration.TOUCHSCREEN_FINGER,
                InputDevice.getDeviceIds().any { id -> InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)==true })
        }
    }
}
object DeviceInputPolicy {
    fun resolve(device: InputDeviceProfile, preference: TouchMode, game: GameTitle): TouchMode {
        if(!device.touchDevice) return TouchMode.OFF
        if(preference==TouchMode.VIRTUAL || preference==TouchMode.OFF) return preference
        return if(game.supportedInputTypes.any { it.equals("NativeTouch",true) }) TouchMode.NATIVE else TouchMode.VIRTUAL
    }
}
