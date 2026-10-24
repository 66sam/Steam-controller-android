package dev.scmapper.controller

import dev.scmapper.mapping.Btn
import kotlin.math.cos
import kotlin.math.sin

/**
 * Wire protocol of the Steam Controller (2015), codename "D0G".
 *
 * Follows Valve's own code as shipped in SDL (SDL_hidapi_steam.c, steam/controller_structs.h).
 */
object Sc2015Protocol {
    const val PRODUCT_WIRED = 0x1102
    const val PRODUCT_DONGLE = 0x1142

    // Feature report message ids (first byte of the feature report payload).
    const val ID_CLEAR_DIGITAL_MAPPINGS = 0x81
    const val ID_SET_SETTINGS_VALUES = 0x87
    const val ID_TRIGGER_HAPTIC_PULSE = 0x8F
    const val ID_TURN_OFF_CONTROLLER = 0x9F
    const val ID_DONGLE_GET_WIRELESS_STATE = 0xB4

    // Settings registers (controller_constants.h).
    const val SETTING_LEFT_TRACKPAD_MODE = 7
    const val SETTING_RIGHT_TRACKPAD_MODE = 8
    const val SETTING_LIZARD_MODE = 9
    const val SETTING_LED_USER_BRIGHTNESS = 45
    const val SETTING_IMU_MODE = 48
    const val SETTING_WIRELESS_PACKET_VERSION = 49
    const val SETTING_SLEEP_INACTIVITY_TIMEOUT = 50
    const val TRACKPAD_NONE = 7

    const val IMU_SEND_ORIENTATION = 0x04
    const val IMU_SEND_RAW_ACCEL = 0x08
    const val IMU_SEND_RAW_GYRO = 0x10

    // Input report types (byte 2 of a USB input report).
    const val REPORT_STATE = 0x01
    const val REPORT_WIRELESS = 0x03
    const val REPORT_STATUS = 0x04
    const val REPORT_BLE_STATE = 0x07
    const val WIRELESS_DISCONNECTED = 1

    // Wire button bits (low 24 bits, same over USB and BLE).
    const val W_RT = 1 shl 0
    const val W_LT = 1 shl 1
    const val W_RB = 1 shl 2
    const val W_LB = 1 shl 3
    const val W_Y = 1 shl 4
    const val W_B = 1 shl 5
    const val W_X = 1 shl 6
    const val W_A = 1 shl 7
    const val W_BACK = 1 shl 12
    const val W_STEAM = 1 shl 13
    const val W_START = 1 shl 14
    const val W_LGRIP = 1 shl 15
    const val W_RGRIP = 1 shl 16
    const val W_LPAD_CLICK = 1 shl 17
    const val W_RPAD_CLICK = 1 shl 18
    const val W_LPAD_TOUCH = 1 shl 19
    const val W_RPAD_TOUCH = 1 shl 20
    const val W_STICK_CLICK = 1 shl 22
    const val W_LPAD_AND_STICK = 1 shl 23

    private val wireToBtn = listOf(
        W_RT to Btn.RT_CLICK, W_LT to Btn.LT_CLICK, W_RB to Btn.RB, W_LB to Btn.LB,
        W_Y to Btn.Y, W_B to Btn.B, W_X to Btn.X, W_A to Btn.A,
        W_BACK to Btn.BACK, W_STEAM to Btn.STEAM, W_START to Btn.START,
        W_LGRIP to Btn.LGRIP, W_RGRIP to Btn.RGRIP,
        W_LPAD_CLICK to Btn.LPAD_CLICK, W_RPAD_CLICK to Btn.RPAD_CLICK,
        W_LPAD_TOUCH to Btn.LPAD_TOUCH, W_RPAD_TOUCH to Btn.RPAD_TOUCH,
        W_STICK_CLICK to Btn.STICK_CLICK,
    )

    fun translate(wire: Int): Int {
        var out = 0
        for ((w, b) in wireToBtn) if (wire and w != 0) out = out or b.bit
        return out
    }

    // BLE GATT.
    const val BLE_SERVICE = "100f6c32-1735-4313-b402-38567131e5f3"
    const val BLE_INPUT_CHAR = "100f6c33-1735-4313-b402-38567131e5f3"
    const val BLE_REPORT_CHAR = "100f6c34-1735-4313-b402-38567131e5f3"
    const val BLE_REPORT_STATE = 0x04
    private const val BLE_CHUNK_BUTTONS1 = 0x0010
    private const val BLE_CHUNK_TRIGGERS = 0x0020
    private const val BLE_CHUNK_BUTTONS3 = 0x0040
    private const val BLE_CHUNK_STICK = 0x0080
    private const val BLE_CHUNK_LPAD = 0x0100
    private const val BLE_CHUNK_RPAD = 0x0200
    private const val BLE_CHUNK_ACCEL = 0x0400
    private const val BLE_CHUNK_GYRO = 0x0800
    private const val BLE_CHUNK_QUAT = 0x1000
    private const val BLE_SEGMENT_PAYLOAD = 18

    /** Steam rotates the raw pad coordinates by 15° (left pad -15°, right pad +15°). */
    private const val PAD_ROTATION_RAD = 0.261799

    private val cosR = cos(PAD_ROTATION_RAD).toFloat()
    private val sinR = sin(PAD_ROTATION_RAD).toFloat()

    private fun s16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    private fun setLeftPad(state: RawState, x: Int, y: Int) {
        // Rotate by -15°.
        state.lpadX = (cosR * x + sinR * y).toInt().coerceIn(-32768, 32767)
        state.lpadY = (-sinR * x + cosR * y).toInt().coerceIn(-32768, 32767)
    }

    private fun setRightPad(state: RawState, x: Int, y: Int) {
        // Rotate by +15°.
        state.rpadX = (cosR * x - sinR * y).toInt().coerceIn(-32768, 32767)
        state.rpadY = (sinR * x + cosR * y).toInt().coerceIn(-32768, 32767)
    }

    /**
     * Parses a 64 byte USB input report into [state].
     * Returns the report type ([REPORT_STATE] for both state formats), or -1 if the packet isn't a
     * Valve report.
     *
     * Layout (ValveInReport_t): u16 version, u8 type, u8 length, then for a state report:
     * u32 packet, u8[8] buttons (bytes 3/4 = triggers), s16 left pad/stick x,y, s16 right pad x,y,
     * then for type 1: u16 triggers L,R, s16 accel xyz, s16 gyro xyz, s16 quaternion wxyz;
     * for type 7 (what the wireless receiver may send): u8 IMU data type, s16[4] IMU data.
     */
    fun parseUsb(b: ByteArray, len: Int, state: RawState): Int {
        if (len < 4 || u8(b, 0) != 0x01) return -1
        val type = u8(b, 2)
        when (type) {
            REPORT_STATE, REPORT_BLE_STATE -> {
                if (len < if (type == REPORT_STATE) 40 else 33) return -1
                val wire = u8(b, 8) or (u8(b, 9) shl 8) or (u8(b, 10) shl 16)
                var buttons = translate(wire)
                state.lt = u8(b, 11)
                state.rt = u8(b, 12)
                val x = s16(b, 16)
                val y = s16(b, 18)
                // The left pad and the stick share these fields. When both are in use the
                // controller alternates and sets W_LPAD_AND_STICK; the touch bit says which one
                // this packet carries.
                val both = wire and W_LPAD_AND_STICK != 0
                if (wire and W_LPAD_TOUCH != 0) {
                    setLeftPad(state, x, y)
                    if (!both) { state.stickX = 0; state.stickY = 0 }
                } else {
                    state.stickX = x; state.stickY = y
                    if (both) buttons = buttons or Btn.LPAD_TOUCH.bit
                    else { state.lpadX = 0; state.lpadY = 0 }
                }
                state.buttons = buttons
                setRightPad(state, s16(b, 20), s16(b, 22))
                if (type == REPORT_STATE) {
                    state.accelX = s16(b, 28); state.accelY = s16(b, 30); state.accelZ = s16(b, 32)
                    state.gyroX = s16(b, 34); state.gyroY = s16(b, 36); state.gyroZ = s16(b, 38)
                } else {
                    when (u8(b, 24)) {
                        2 -> { state.accelX = s16(b, 25); state.accelY = s16(b, 27); state.accelZ = s16(b, 29) }
                        3 -> { state.gyroX = s16(b, 25); state.gyroY = s16(b, 27); state.gyroZ = s16(b, 29) }
                    }
                }
                return REPORT_STATE
            }
            REPORT_STATUS -> {
                if (len >= 15) {
                    val level = u8(b, 14)
                    if (level in 0..100) state.battery = level
                }
            }
        }
        return type
    }

    /**
     * Reassembles BLE notifications. Each notification is one segment: a header byte
     * (0x80 = data, 0x40 = last segment, low 3 bits = segment number) followed by up to 18 payload
     * bytes; a report spans one or more segments. Mirrors SDL's packet assembler.
     */
    class BleAssembler {
        private val buffer = ByteArray(8 * BLE_SEGMENT_PAYLOAD)
        private var expected = 0

        /** Returns the complete report when [notification] finishes one, otherwise null. */
        fun add(notification: ByteArray): ByteArray? {
            var o = 0
            // Some stacks prepend the HID report number (0x03).
            if (notification.size == BLE_SEGMENT_PAYLOAD + 2 && notification[0].toInt() == 0x03) o = 1
            if (notification.size <= o) return null
            val header = notification[o].toInt() and 0xFF
            if (header and 0x80 == 0) return null // empty segment
            val segment = header and 0x07
            if (segment != expected) {
                expected = 0
                buffer.fill(0)
                if (segment != 0) return null
            }
            val n = minOf(BLE_SEGMENT_PAYLOAD, notification.size - o - 1)
            System.arraycopy(notification, o + 1, buffer, segment * BLE_SEGMENT_PAYLOAD, n)
            if (header and 0x40 != 0) {
                expected = 0
                return buffer.copyOf((segment + 1) * BLE_SEGMENT_PAYLOAD)
            }
            expected = segment + 1
            return null
        }
    }

    /** Parses a reassembled BLE report (see [BleAssembler]). Returns true on a state report. */
    fun parseBle(raw: ByteArray, state: RawState): Boolean {
        var o = 0
        // Some stacks prepend the HID report number (0x03).
        if (raw.isNotEmpty() && (u8(raw, 0) and 0x0F) != BLE_REPORT_STATE && u8(raw, 0) == 0x03) o = 1
        if (raw.size < o + 2 || (u8(raw, o) and 0x0F) != BLE_REPORT_STATE) return false
        val mask = (u8(raw, o) and 0xF0) or (u8(raw, o + 1) shl 8)
        o += 2
        fun has(chunk: Int, size: Int) = mask and chunk != 0 && o + size <= raw.size
        if (has(BLE_CHUNK_BUTTONS1, 3)) {
            state.buttons = translate(u8(raw, o) or (u8(raw, o + 1) shl 8) or (u8(raw, o + 2) shl 16))
            o += 3
        }
        if (has(BLE_CHUNK_TRIGGERS, 2)) { state.lt = u8(raw, o); state.rt = u8(raw, o + 1); o += 2 }
        if (has(BLE_CHUNK_BUTTONS3, 3)) o += 3
        if (has(BLE_CHUNK_STICK, 4)) { state.stickX = s16(raw, o); state.stickY = s16(raw, o + 2); o += 4 }
        if (has(BLE_CHUNK_LPAD, 4)) { setLeftPad(state, s16(raw, o), s16(raw, o + 2)); o += 4 }
        if (has(BLE_CHUNK_RPAD, 4)) { setRightPad(state, s16(raw, o), s16(raw, o + 2)); o += 4 }
        if (has(BLE_CHUNK_ACCEL, 6)) {
            state.accelX = s16(raw, o); state.accelY = s16(raw, o + 2); state.accelZ = s16(raw, o + 4); o += 6
        }
        if (has(BLE_CHUNK_GYRO, 6)) {
            state.gyroX = s16(raw, o); state.gyroY = s16(raw, o + 2); state.gyroZ = s16(raw, o + 4); o += 6
        }
        if (has(BLE_CHUNK_QUAT, 8)) o += 8
        if (!state.pressed(Btn.LPAD_TOUCH)) { state.lpadX = 0; state.lpadY = 0 }
        if (!state.pressed(Btn.RPAD_TOUCH)) { state.rpadX = 0; state.rpadY = 0 }
        return true
    }

    // ---- Feature messages. Each returns the payload starting with the message id. ----

    fun clearDigitalMappings() = byteArrayOf(ID_CLEAR_DIGITAL_MAPPINGS.toByte(), 0)

    /** Asks the wireless receiver to report whether a controller is connected to this slot. */
    fun getWirelessState() = byteArrayOf(ID_DONGLE_GET_WIRELESS_STATE.toByte(), 0)

    fun settings(vararg regs: Pair<Int, Int>): ByteArray {
        val out = ByteArray(2 + regs.size * 3)
        out[0] = ID_SET_SETTINGS_VALUES.toByte()
        out[1] = (regs.size * 3).toByte()
        regs.forEachIndexed { i, (reg, value) ->
            out[2 + i * 3] = reg.toByte()
            out[3 + i * 3] = (value and 0xFF).toByte()
            out[4 + i * 3] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    /** Disables "lizard mode" (built-in keyboard/mouse emulation) and configures IMU reporting. */
    fun configure(gyro: Boolean, ble: Boolean): ByteArray {
        val imuMode = when {
            !gyro -> 0
            ble -> IMU_SEND_RAW_GYRO
            else -> IMU_SEND_RAW_GYRO or IMU_SEND_RAW_ACCEL or IMU_SEND_ORIENTATION
        }
        return settings(
            SETTING_LEFT_TRACKPAD_MODE to TRACKPAD_NONE,
            SETTING_RIGHT_TRACKPAD_MODE to TRACKPAD_NONE,
            SETTING_SLEEP_INACTIVITY_TIMEOUT to 900,
            // As SDL does: the receiver then sends the full state report.
            SETTING_WIRELESS_PACKET_VERSION to 2,
            SETTING_IMU_MODE to imuMode,
        )
    }

    fun ledBrightness(percent: Int) = settings(SETTING_LED_USER_BRIGHTNESS to percent.coerceIn(0, 100))

    /**
     * Haptic pulse on one trackpad actuator.
     * @param right true for the right pad
     * @param onUs high time of each pulse (≈ amplitude), in microseconds
     * @param offUs low time between pulses, in microseconds
     * @param count number of pulses
     */
    fun hapticPulse(right: Boolean, onUs: Int, offUs: Int, count: Int): ByteArray {
        val on = onUs.coerceIn(0, 0xFFFF)
        val off = offUs.coerceIn(0, 0xFFFF)
        val n = count.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            ID_TRIGGER_HAPTIC_PULSE.toByte(), 0x07, if (right) 0 else 1,
            on.toByte(), (on shr 8).toByte(), off.toByte(), (off shr 8).toByte(), n.toByte(), (n shr 8).toByte(),
        )
    }

    fun turnOff() = byteArrayOf(ID_TURN_OFF_CONTROLLER.toByte(), 0x04, 0x6f, 0x66, 0x66, 0x21)

    /** Pads a feature message to the 64 byte USB feature report (report id 0). */
    fun usbFeature(msg: ByteArray): ByteArray = msg.copyOf(64)

    /** Splits a feature message into BLE report-characteristic writes (segmented, 19 bytes each). */
    fun bleSegments(msg: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var offset = 0
        var segment = 0
        do {
            val n = minOf(BLE_SEGMENT_PAYLOAD, msg.size - offset)
            val last = offset + n >= msg.size
            val packet = ByteArray(1 + BLE_SEGMENT_PAYLOAD)
            packet[0] = (0x80 or segment or (if (last) 0x40 else 0)).toByte()
            System.arraycopy(msg, offset, packet, 1, n)
            out += packet
            offset += n
            segment++
        } while (!last)
        return out
    }
}
