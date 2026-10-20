package dev.scmapper

import android.content.Context
import dev.scmapper.controller.GenericMapping
import dev.scmapper.mapping.enumOr
import org.json.JSONObject
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

    /** Other gamepads (by [dev.scmapper.controller.EvdevDevice.key]) that SC Mapper takes over. */
    var genericDevices: Set<String>
        get() = prefs.getStringSet("generic", emptySet()).orEmpty().toSet()
        set(value) = prefs.edit().putStringSet("generic", value).apply()

    fun genericMapping(key: String): GenericMapping? = prefs.getString("map:$key", null)?.let {
        try { GenericMapping.fromJson(JSONObject(it)) } catch (_: Exception) { null }
    }

    fun setGenericMapping(key: String, m: GenericMapping?) {
        if (m == null) prefs.edit().remove("map:$key").apply()
        else prefs.edit().putString("map:$key", m.toJson().toString()).apply()
    }

    /** Show a toast when switching profiles with the controller. */
    var toasts: Boolean
        get() = prefs.getBoolean("toasts", true)
        set(value) = prefs.edit().putBoolean("toasts", value).apply()
}
