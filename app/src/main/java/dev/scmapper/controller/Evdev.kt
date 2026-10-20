package dev.scmapper.controller

import dev.scmapper.mapping.Btn
import dev.scmapper.mapping.enumOrNull
import org.json.JSONObject

class AbsInfo(val min: Int, val max: Int, val flat: Int, val resolution: Int)

/** One Linux input device as reported by `getevent -i`. */
class EvdevDevice(
    val path: String,
    val name: String,
    val bus: Int,
    val vendor: Int,
    val product: Int,
    val location: String,
    val uniq: String,
    val keys: Set<Int>,
    val abs: Map<Int, AbsInfo>,
) {
    /** Stable identity across reconnects (device paths change). */
    val key: String get() = "%04x:%04x:%s".format(vendor, product, name)

    val isGamepad: Boolean
        get() = keys.any { it in 0x130..0x13e || it in 0x120..0x12f } && Evdev.ABS_X in abs && Evdev.ABS_Y in abs

    /** Separate accelerometer/gyro device that some drivers (PlayStation, Nintendo) create. */
    val isMotionSensor: Boolean
        get() = Evdev.ABS_RX in abs && Evdev.ABS_X in abs && keys.isEmpty() &&
            (name.contains("Motion Sensors", true) || name.endsWith(" IMU", true))

    override fun toString() = "$name ($path)"
}

object Evdev {
    const val EV_KEY = 1
    const val EV_ABS = 3

    const val ABS_X = 0x00
    const val ABS_Y = 0x01
    const val ABS_Z = 0x02
    const val ABS_RX = 0x03
    const val ABS_RY = 0x04
    const val ABS_RZ = 0x05
    const val ABS_GAS = 0x09
    const val ABS_BRAKE = 0x0a
    const val ABS_HAT0X = 0x10
    const val ABS_HAT0Y = 0x11

    private val header = Regex("^\\s+([A-Z_?]+)\\s*\\(([0-9a-fA-F]{4})\\):(.*)$")
    private val absLine = Regex("([0-9a-fA-F]{4})\\s*:\\s*value\\s+(-?\\d+),\\s*min\\s+(-?\\d+),\\s*max\\s+(-?\\d+),\\s*fuzz\\s+(-?\\d+),\\s*flat\\s+(-?\\d+)(?:,\\s*resolution\\s+(-?\\d+))?")
    private val hex4 = Regex("\\b[0-9a-fA-F]{4}\\b")

    /** Parses the output of `getevent -i` (no labels). */
    fun parse(text: String): List<EvdevDevice> {
        val out = ArrayList<EvdevDevice>()
        var path: String? = null
        var name = ""
        var bus = 0
        var vendor = 0
        var product = 0
        var location = ""
        var uniq = ""
        var keys = HashSet<Int>()
        var abs = HashMap<Int, AbsInfo>()
        var type = -1
        var inEvents = false
        fun flush() {
            val p = path ?: return
            out += EvdevDevice(p, name, bus, vendor, product, location, uniq, keys, abs)
        }
        for (line in text.lines()) {
            if (line.startsWith("add device")) {
                flush()
                path = line.substringAfter(':').trim()
                name = ""; bus = 0; vendor = 0; product = 0; location = ""; uniq = ""
                keys = HashSet(); abs = HashMap(); type = -1; inEvents = false
                continue
            }
            val t = line.trim()
            when {
                t.startsWith("bus:") -> bus = t.substringAfter(':').trim().toIntOrNull(16) ?: 0
                t.startsWith("vendor") -> vendor = t.removePrefix("vendor").trim().toIntOrNull(16) ?: 0
                t.startsWith("product") -> product = t.removePrefix("product").trim().toIntOrNull(16) ?: 0
                t.startsWith("name:") -> name = t.substringAfter(':').trim().removeSurrounding("\"")
                t.startsWith("location:") -> location = t.substringAfter(':').trim().removeSurrounding("\"")
                t.startsWith("id:") -> uniq = t.substringAfter(':').trim().removeSurrounding("\"")
                t == "events:" -> inEvents = true
                t.startsWith("input props:") -> { inEvents = false; type = -1 }
                inEvents -> {
                    val h = header.find(line)
                    val rest = if (h != null) {
                        type = h.groupValues[2].toInt(16)
                        h.groupValues[3]
                    } else {
                        line
                    }
                    when (type) {
                        EV_KEY -> hex4.findAll(rest).forEach { keys += it.value.toInt(16) }
                        EV_ABS -> absLine.find(rest)?.let { m ->
                            abs[m.groupValues[1].toInt(16)] = AbsInfo(
                                m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[6].toInt(),
                                m.groupValues[7].toIntOrNull() ?: 0,
                            )
                        }
                    }
                }
            }
        }
        flush()
        return out
    }
}

enum class AxisRole(val label: String) {
    NONE("Unused"),
    LX("Left stick X"), LY("Left stick Y"),
    RX("Right stick X"), RY("Right stick Y"),
    LT("Left trigger"), RT("Right trigger"),
    HAT_X("D-pad X"), HAT_Y("D-pad Y"),
}

/** How one generic controller's evdev codes map onto SC Mapper's inputs. */
class GenericMapping(
    val buttons: MutableMap<Int, Btn> = HashMap(),
    val axes: MutableMap<Int, AxisRole> = HashMap(),
    val inverted: MutableSet<Int> = HashSet(),
) {
    fun toJson(): JSONObject {
        val b = JSONObject()
        buttons.forEach { (code, btn) -> b.put(code.toString(), btn.name) }
        val a = JSONObject()
        axes.forEach { (code, role) -> a.put(code.toString(), role.name) }
        return JSONObject().put("buttons", b).put("axes", a).put("inverted", inverted.joinToString(","))
    }

    companion object {
        fun fromJson(o: JSONObject): GenericMapping {
            val m = GenericMapping()
            o.optJSONObject("buttons")?.let { b -> for (k in b.keys()) enumOrNull<Btn>(b.optString(k))?.let { m.buttons[k.toInt()] = it } }
            o.optJSONObject("axes")?.let { a -> for (k in a.keys()) enumOrNull<AxisRole>(a.optString(k))?.let { m.axes[k.toInt()] = it } }
            o.optString("inverted").split(',').mapNotNull { it.trim().toIntOrNull() }.forEach { m.inverted += it }
            return m
        }

        private const val VENDOR_SONY = 0x054c
        private const val VENDOR_NINTENDO = 0x057e

        /**
         * Linux gamepad conventions. Drivers that follow the spec (PlayStation, Nintendo) put the top
         * face button on BTN_NORTH (0x133) and the left one on BTN_WEST (0x134); Xbox-style drivers
         * (xpad, generic HID) send X on 0x133 and Y on 0x134. Buttons are mapped by position.
         */
        fun defaults(d: EvdevDevice): GenericMapping {
            val m = GenericMapping()
            val spec = d.vendor == VENDOR_SONY || d.vendor == VENDOR_NINTENDO
            val table = mapOf(
                0x130 to Btn.A, 0x131 to Btn.B,
                0x133 to (if (spec) Btn.Y else Btn.X), 0x134 to (if (spec) Btn.X else Btn.Y),
                0x136 to Btn.LB, 0x137 to Btn.RB, 0x138 to Btn.LT_CLICK, 0x139 to Btn.RT_CLICK,
                0x13a to Btn.BACK, 0x13b to Btn.START, 0x13c to Btn.STEAM,
                0x13d to Btn.STICK_CLICK, 0x13e to Btn.RSTICK_CLICK,
                0x220 to Btn.DPAD_UP, 0x221 to Btn.DPAD_DOWN, 0x222 to Btn.DPAD_LEFT, 0x223 to Btn.DPAD_RIGHT,
                158 to Btn.BACK, 172 to Btn.STEAM, 167 to Btn.QAM,
                0x2c0 to Btn.LGRIP, 0x2c1 to Btn.RGRIP, 0x2c2 to Btn.LGRIP2, 0x2c3 to Btn.RGRIP2,
                0x2c4 to Btn.LGRIP, 0x2c5 to Btn.RGRIP, 0x2c6 to Btn.LGRIP2, 0x2c7 to Btn.RGRIP2,
            )
            for ((code, btn) in table) if (code in d.keys) m.buttons[code] = btn
            m.axes[Evdev.ABS_X] = AxisRole.LX
            m.axes[Evdev.ABS_Y] = AxisRole.LY
            when {
                Evdev.ABS_GAS in d.abs || Evdev.ABS_BRAKE in d.abs -> {
                    m.axes[Evdev.ABS_Z] = AxisRole.RX; m.axes[Evdev.ABS_RZ] = AxisRole.RY
                    m.axes[Evdev.ABS_BRAKE] = AxisRole.LT; m.axes[Evdev.ABS_GAS] = AxisRole.RT
                }
                Evdev.ABS_RX in d.abs || Evdev.ABS_RY in d.abs -> {
                    m.axes[Evdev.ABS_RX] = AxisRole.RX; m.axes[Evdev.ABS_RY] = AxisRole.RY
                    m.axes[Evdev.ABS_Z] = AxisRole.LT; m.axes[Evdev.ABS_RZ] = AxisRole.RT
                }
                else -> { m.axes[Evdev.ABS_Z] = AxisRole.RX; m.axes[Evdev.ABS_RZ] = AxisRole.RY }
            }
            m.axes[Evdev.ABS_HAT0X] = AxisRole.HAT_X
            m.axes[Evdev.ABS_HAT0Y] = AxisRole.HAT_Y
            m.axes.keys.retainAll(d.abs.keys)
            return m
        }
    }
}

/**
 * Applies evdev events to a [RawState] using a [GenericMapping]. Pure logic, kept separate from the
 * transport so it can be unit tested.
 */
class EvdevTranslator(private val device: EvdevDevice, var mapping: GenericMapping) {
    private var keyButtons = 0
    private var hatX = 0
    private var hatY = 0
    /** Last raw value per absolute axis, for the setup wizard. */
    val lastAbs: MutableMap<Int, Int> = java.util.concurrent.ConcurrentHashMap()

    fun apply(events: IntArray, state: RawState) {
        var i = 0
        while (i + 2 < events.size) {
            val type = events[i]; val code = events[i + 1]; val value = events[i + 2]
            i += 3
            when (type) {
                Evdev.EV_KEY -> {
                    val btn = mapping.buttons[code] ?: continue
                    keyButtons = if (value != 0) keyButtons or btn.bit else keyButtons and btn.bit.inv()
                }
                Evdev.EV_ABS -> {
                    lastAbs[code] = value
                    val info = device.abs[code] ?: continue
                    val role = mapping.axes[code] ?: continue
                    val inv = code in mapping.inverted
                    when (role) {
                        AxisRole.NONE -> {}
                        AxisRole.LX -> state.stickX = centered(value, info, inv)
                        AxisRole.LY -> state.stickY = -centered(value, info, inv) // evdev Y is down-positive
                        AxisRole.RX -> state.rstickX = centered(value, info, inv)
                        AxisRole.RY -> state.rstickY = -centered(value, info, inv)
                        AxisRole.LT -> state.lt = trigger(value, info, inv)
                        AxisRole.RT -> state.rt = trigger(value, info, inv)
                        AxisRole.HAT_X -> hatX = sign(value, info, inv)
                        AxisRole.HAT_Y -> hatY = sign(value, info, inv)
                    }
                }
            }
        }
        var hat = 0
        if (hatY < 0) hat = hat or Btn.DPAD_UP.bit
        if (hatY > 0) hat = hat or Btn.DPAD_DOWN.bit
        if (hatX < 0) hat = hat or Btn.DPAD_LEFT.bit
        if (hatX > 0) hat = hat or Btn.DPAD_RIGHT.bit
        state.buttons = keyButtons or hat
    }

    private fun centered(v: Int, a: AbsInfo, inv: Boolean): Int {
        val span = (a.max - a.min).coerceAtLeast(1)
        var n = ((v - a.min) * 2.0 / span - 1.0)
        if (inv) n = -n
        return (n * 32767).toInt().coerceIn(-32768, 32767)
    }

    private fun trigger(v: Int, a: AbsInfo, inv: Boolean): Int {
        val span = (a.max - a.min).coerceAtLeast(1)
        var n = (v - a.min).toDouble() / span
        if (inv) n = 1 - n
        return (n * 255).toInt().coerceIn(0, 255)
    }

    private fun sign(v: Int, a: AbsInfo, inv: Boolean): Int {
        val center = (a.min + a.max) / 2.0
        val s = if (v > center + 0.25) 1 else if (v < center - 0.25) -1 else 0
        return if (inv) -s else s
    }

    /** Motion sensor device: gyro axes with resolution in units per °/s. */
    fun applyMotion(events: IntArray, motion: EvdevDevice, state: RawState) {
        var i = 0
        while (i + 2 < events.size) {
            val type = events[i]; val code = events[i + 1]; val value = events[i + 2]
            i += 3
            if (type != Evdev.EV_ABS) continue
            val res = motion.abs[code]?.resolution?.takeIf { it > 0 } ?: continue
            // To the controllers' native scale (±2000 °/s in 16 bits).
            val raw = (value.toDouble() / res * 16.384).toInt().coerceIn(-32768, 32767)
            when (code) {
                Evdev.ABS_RX -> state.gyroX = raw
                Evdev.ABS_RY -> state.gyroZ = raw
                Evdev.ABS_RZ -> state.gyroY = raw
            }
        }
    }
}
