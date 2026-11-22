package com.rembrowser.rembrowse.engine

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice

/** The pointing devices connected to this device, as far as the engine's pointer reporting goes. */
internal object PointerDevices {

    /**
     * True when a real (not virtual) mouse or touchpad is connected: a laptop's touchpad, or a
     * mouse on a tablet or phone. A device that is also a touchscreen or a stylus doesn't count:
     * some touchscreens and pens report a mouse source too (Gecko's own GeckoAppShell notes it for
     * ChromeOS touchscreens), and pen phones should keep reporting a coarse pointer. False when the
     * system can't be asked.
     */
    fun hasMouseOrTouchpad(context: Context): Boolean = runCatching {
        val inputManager = context.getSystemService(InputManager::class.java) ?: return false
        inputManager.inputDeviceIds.any { id ->
            val device = inputManager.getInputDevice(id) ?: return@any false
            !device.isVirtual &&
                !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) &&
                !device.supportsSource(InputDevice.SOURCE_STYLUS) &&
                (device.supportsSource(InputDevice.SOURCE_MOUSE) || device.supportsSource(InputDevice.SOURCE_TOUCHPAD))
        }
    }.getOrDefault(false)
}
