package dev.scmapper

import android.content.Context
import dev.scmapper.mapping.enumOr
import dev.scmapper.output.HidDescriptors.GamepadIdentity

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var identity: GamepadIdentity
        get() = enumOr(prefs.getString("identity", null), GamepadIdentity.XBOX_ONE_BT)
        set(value) = prefs.edit().putString("identity", value.name).apply()

    /** Bluetooth controllers the service keeps a connection armed for. */
    var bleAddresses: Set<String>
        get() = prefs.getStringSet("ble", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("ble", value).apply()

    /** Show a toast when switching profiles with the controller. */
    var toasts: Boolean
        get() = prefs.getBoolean("toasts", true)
        set(value) = prefs.edit().putBoolean("toasts", value).apply()
}
