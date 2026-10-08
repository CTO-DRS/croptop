package app.croptop.core.designsystem.theme

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** User preference; [Auto] follows attached input devices. */
enum class DensityPreference { Auto, Pointer, Touch }

/** True when any enabled mouse, touchpad or stylus-as-pointer device is attached. */
fun hasPointerDevice(context: Context): Boolean {
    val im = context.getSystemService(InputManager::class.java) ?: return false
    return im.inputDeviceIds.any { id ->
        val d = im.getInputDevice(id) ?: return@any false
        d.isEnabled && !d.isVirtual &&
            (d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_TOUCHPAD))
    }
}

/** Resolves [preference] to a density, re-evaluating as keyboards/mice attach and detach. */
@Composable
fun rememberAeDensity(preference: DensityPreference = DensityPreference.Auto): AeDensity {
    val context = LocalContext.current
    var pointer by remember { mutableStateOf(hasPointerDevice(context)) }
    DisposableEffect(context) {
        val im = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) {
                pointer = hasPointerDevice(context)
            }
            override fun onInputDeviceRemoved(deviceId: Int) {
                pointer = hasPointerDevice(context)
            }
            override fun onInputDeviceChanged(deviceId: Int) {
                pointer = hasPointerDevice(context)
            }
        }
        im?.registerInputDeviceListener(listener, null)
        onDispose { im?.unregisterInputDeviceListener(listener) }
    }
    return when (preference) {
        DensityPreference.Pointer -> AeDensity.Pointer
        DensityPreference.Touch -> AeDensity.Touch
        DensityPreference.Auto -> if (pointer) AeDensity.Pointer else AeDensity.Touch
    }
}
