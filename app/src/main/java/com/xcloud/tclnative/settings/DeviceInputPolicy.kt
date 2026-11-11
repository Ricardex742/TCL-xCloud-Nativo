package com.xcloud.tclnative.settings

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.xcloud.tclnative.signaling.GameTitle

/** One decision for catalog launches and shortcuts. TV detection takes precedence over touchscreen flags. */
data class InputDeviceProfile(val television: Boolean,val touchscreen: Boolean) {
    val touchDevice: Boolean get()=!television && touchscreen
    companion object {
        fun read(context: Context): InputDeviceProfile {
            val pm=context.packageManager
            val mode=(context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
            return InputDeviceProfile(mode==Configuration.UI_MODE_TYPE_TELEVISION ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION),
                pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN))
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
