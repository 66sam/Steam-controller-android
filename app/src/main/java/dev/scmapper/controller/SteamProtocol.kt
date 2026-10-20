package dev.scmapper.controller

/**
 * Wire protocol of the original (2015) Steam Controller.
 *
 * Sources: Valve's HID code as shipped in SDL (SDL_hidapi_steam.c / steam/controller_constants.h)
 * and the community projects sc-controller and steamcontroller (ynsta).
 */
object SteamProtocol {
    const val VENDOR_VALVE = 0x28DE
    const val PRODUCT_WIRED = 0x1102
    const val PRODUCT_DONGLE = 0x1142

    // Feature report message ids (first byte of the 64 byte feature report payload).
    const val ID_CLEAR_DIGITAL_MAPPINGS = 0x81
    const val ID_LOAD_DEFAULT_SETTINGS = 0x85
    const val ID_SET_SETTINGS_VALUES = 0x87
    const val ID_TRIGGER_HAPTIC_PULSE = 0x8F
    const val ID_TURN_OFF_CONTROLLER = 0x9F

    // Settings registers for ID_SET_SETTINGS_VALUES.
    const val SETTING_LEFT_TRACKPAD_MODE = 0x07
    const val SETTING_RIGHT_TRACKPAD_MODE = 0x08
    const val SETTING_LED_USER_BRIGHTNESS = 0x2D
    const val SETTING_GYRO_MODE = 0x30
    const val SETTING_SLEEP_INACTIVITY_TIMEOUT = 0x32
    const val TRACKPAD_NONE = 0x07

    const val GYRO_SEND_ORIENTATION = 0x04
    const val GYRO_SEND_RAW_ACCEL = 0x08
    const val GYRO_SEND_RAW_GYRO = 0x10

    // Input report types (byte 2 of a USB input report).
    const val REPORT_STATE = 0x01
    const val REPORT_WIRELESS = 0x03
    const val REPORT_STATUS = 0x04

    // Button bits, identical for USB and BLE in the low 24 bits.
    const val B_RT = 1 shl 0
    const val B_LT = 1 shl 1
    const val B_RB = 1 shl 2
    const val B_LB = 1 shl 3
    const val B_Y = 1 shl 4
    const val B_B = 1 shl 5
    const val B_X = 1 shl 6
    const val B_A = 1 shl 7
    const val B_BACK = 1 shl 12
    const val B_STEAM = 1 shl 13
    const val B_START = 1 shl 14
    const val B_LGRIP = 1 shl 15
    const val B_RGRIP = 1 shl 16
    const val B_LPAD_CLICK = 1 shl 17
    const val B_RPAD_CLICK = 1 shl 18
    const val B_LPAD_TOUCH = 1 shl 19
    const val B_RPAD_TOUCH = 1 shl 20
    const val B_STICK_CLICK = 1 shl 22
    const val B_LPAD_AND_STICK = 1 shl 23

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

    private fun s16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

    /**
     * Parses a 64 byte USB input report into [state].
     * Returns the report type, or -1 if the packet isn't a Valve report.
     */
    fun parseUsb(b: ByteArray, len: Int, state: RawState): Int {
        if (len < 4 || u8(b, 0) != 0x01) return -1
        val type = u8(b, 2)
        when (type) {
            REPORT_STATE -> {
                if (len < 44) return -1
                val buttons = u8(b, 8) or (u8(b, 9) shl 8) or (u8(b, 10) shl 16)
                state.buttons = buttons and B_LPAD_AND_STICK.inv()
                state.lt = u8(b, 11)
                state.rt = u8(b, 12)
                val x = s16(b, 16)
                val y = s16(b, 18)
                // The left pad and the stick share these fields. When both are in use the
                // controller alternates and sets B_LPAD_AND_STICK; the touch bit says which one
                // this packet carries.
                val both = buttons and B_LPAD_AND_STICK != 0
                if (buttons and B_LPAD_TOUCH != 0) {
                    state.lpadX = x; state.lpadY = y
                    if (!both) { state.stickX = 0; state.stickY = 0 }
                } else {
                    state.stickX = x; state.stickY = y
                    if (both) state.buttons = state.buttons or B_LPAD_TOUCH
                    else { state.lpadX = 0; state.lpadY = 0 }
                }
                state.rpadX = s16(b, 20); state.rpadY = s16(b, 22)
                state.accelX = s16(b, 24); state.accelY = s16(b, 26); state.accelZ = s16(b, 28)
                state.gyroX = s16(b, 30); state.gyroY = s16(b, 32); state.gyroZ = s16(b, 34)
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

    /** Parses a BLE notification from the Valve input characteristic. Returns true on a state report. */
    fun parseBle(raw: ByteArray, state: RawState): Boolean {
        var o = 0
        // Some stacks prepend the HID report number (0x03).
        if (raw.isNotEmpty() && (u8(raw, 0) and 0x0F) != BLE_REPORT_STATE && u8(raw, 0) == 0x03) o = 1
        if (raw.size < o + 2 || (u8(raw, o) and 0x0F) != BLE_REPORT_STATE) return false
        val mask = (u8(raw, o) and 0xF0) or (u8(raw, o + 1) shl 8)
        o += 2
        fun has(chunk: Int, size: Int) = mask and chunk != 0 && o + size <= raw.size
        if (has(BLE_CHUNK_BUTTONS1, 3)) {
            state.buttons = (u8(raw, o) or (u8(raw, o + 1) shl 8) or (u8(raw, o + 2) shl 16)) and B_LPAD_AND_STICK.inv()
            o += 3
        }
        if (has(BLE_CHUNK_TRIGGERS, 2)) { state.lt = u8(raw, o); state.rt = u8(raw, o + 1); o += 2 }
        if (has(BLE_CHUNK_BUTTONS3, 3)) o += 3
        if (has(BLE_CHUNK_STICK, 4)) { state.stickX = s16(raw, o); state.stickY = s16(raw, o + 2); o += 4 }
        if (has(BLE_CHUNK_LPAD, 4)) { state.lpadX = s16(raw, o); state.lpadY = s16(raw, o + 2); o += 4 }
        if (has(BLE_CHUNK_RPAD, 4)) { state.rpadX = s16(raw, o); state.rpadY = s16(raw, o + 2); o += 4 }
        if (has(BLE_CHUNK_ACCEL, 6)) {
            state.accelX = s16(raw, o); state.accelY = s16(raw, o + 2); state.accelZ = s16(raw, o + 4); o += 6
        }
        if (has(BLE_CHUNK_GYRO, 6)) {
            state.gyroX = s16(raw, o); state.gyroY = s16(raw, o + 2); state.gyroZ = s16(raw, o + 4); o += 6
        }
        if (has(BLE_CHUNK_QUAT, 8)) o += 8
        if (state.buttons and B_LPAD_TOUCH == 0) { state.lpadX = 0; state.lpadY = 0 }
        if (state.buttons and B_RPAD_TOUCH == 0) { state.rpadX = 0; state.rpadY = 0 }
        return true
    }

    // ---- Feature messages. Each returns the payload starting with the message id. ----

    fun clearDigitalMappings() = byteArrayOf(ID_CLEAR_DIGITAL_MAPPINGS.toByte(), 0)

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
        val gyroMode = when {
            !gyro -> 0
            ble -> GYRO_SEND_RAW_GYRO
            else -> GYRO_SEND_RAW_GYRO or GYRO_SEND_RAW_ACCEL or GYRO_SEND_ORIENTATION
        }
        return settings(
            SETTING_LEFT_TRACKPAD_MODE to TRACKPAD_NONE,
            SETTING_RIGHT_TRACKPAD_MODE to TRACKPAD_NONE,
            SETTING_SLEEP_INACTIVITY_TIMEOUT to 900,
            SETTING_GYRO_MODE to gyroMode,
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

    /** Pads a feature message to the 64 byte USB feature report. */
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
