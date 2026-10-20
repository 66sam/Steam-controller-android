package dev.scmapper.controller

import dev.scmapper.mapping.Btn

/**
 * Wire protocol of the Steam Controller (2026), codename "Triton".
 *
 * Ported from Valve's driver in SDL (SDL_hidapi_steam_triton.c, steam/controller_structs.h,
 * android-project HIDDeviceBLESteamController.java).
 */
object Sc2026Protocol {
    const val PRODUCT_WIRED = 0x1302
    const val PRODUCT_BLE = 0x1303
    /** Wireless receivers ("Proteus" and "Nereid"); controllers sit on interfaces 2..5. */
    const val PRODUCT_DONGLE_PROTEUS = 0x1304
    const val PRODUCT_DONGLE_NEREID = 0x1305

    fun isDongle(pid: Int) = pid == PRODUCT_DONGLE_PROTEUS || pid == PRODUCT_DONGLE_NEREID

    // Input report ids (first byte).
    const val ID_STATE = 0x42
    const val ID_BATTERY = 0x43
    const val ID_STATE_BLE = 0x45
    const val ID_WIRELESS_X = 0x46
    const val ID_STATE_TIMESTAMP = 0x47
    const val ID_WIRELESS = 0x79
    const val WIRELESS_DISCONNECT = 1
    const val WIRELESS_CONNECT = 2

    // Output report ids.
    const val ID_OUT_RUMBLE = 0x80
    const val ID_OUT_HAPTIC_PULSE = 0x81

    /** Feature reports use report id 1, then the same message format as the 2015 controller. */
    const val FEATURE_REPORT_ID = 1

    /** The controller re-enables lizard mode after a watchdog timeout, so this is resent periodically. */
    const val LIZARD_RESEND_MS = 3000L
    /** Rumble stops by itself after ~50 ms, so it is resent while active. */
    const val RUMBLE_RESEND_MS = 40L

    // BLE characteristics in the Valve service (same service UUID as the 2015 controller).
    const val BLE_INPUT_CHAR_45 = "100f6c7a-1735-4313-b402-38567131e5f3"
    const val BLE_INPUT_CHAR_47 = "100f6c7c-1735-4313-b402-38567131e5f3"

    /** Output report N is written to characteristic 100F6C(N + 0x35). */
    fun bleOutputCharFor(reportId: Int): String =
        "100f6c%02x-1735-4313-b402-38567131e5f3".format(reportId + 0x35)

    // Button bits (TritonButtons in SDL).
    private const val T_A = 0x00000001
    private const val T_B = 0x00000002
    private const val T_X = 0x00000004
    private const val T_Y = 0x00000008
    private const val T_QAM = 0x00000010
    private const val T_R3 = 0x00000020
    private const val T_VIEW = 0x00000040
    private const val T_R4 = 0x00000080
    private const val T_R5 = 0x00000100
    private const val T_R = 0x00000200
    private const val T_DPAD_DOWN = 0x00000400
    private const val T_DPAD_RIGHT = 0x00000800
    private const val T_DPAD_LEFT = 0x00001000
    private const val T_DPAD_UP = 0x00002000
    private const val T_MENU = 0x00004000
    private const val T_L3 = 0x00008000
    private const val T_STEAM = 0x00010000
    private const val T_L4 = 0x00020000
    private const val T_L5 = 0x00040000
    private const val T_L = 0x00080000
    private const val T_RSTICK_TOUCH = 0x00100000
    private const val T_RPAD_TOUCH = 0x00200000
    private const val T_RPAD_CLICK = 0x00400000
    private const val T_RT_CLICK = 0x00800000
    private const val T_LSTICK_TOUCH = 0x01000000
    private const val T_LPAD_TOUCH = 0x02000000
    private const val T_LPAD_CLICK = 0x04000000
    private const val T_LT_CLICK = 0x08000000
    private const val T_RGRIP_TOUCH = 0x10000000
    private const val T_LGRIP_TOUCH = 0x20000000

    // SDL maps MENU to Back and VIEW to Start for this controller.
    private val wireToBtn = listOf(
        T_A to Btn.A, T_B to Btn.B, T_X to Btn.X, T_Y to Btn.Y,
        T_L to Btn.LB, T_R to Btn.RB, T_MENU to Btn.BACK, T_VIEW to Btn.START,
        T_STEAM to Btn.STEAM, T_QAM to Btn.QAM,
        T_L4 to Btn.LGRIP, T_R4 to Btn.RGRIP, T_L5 to Btn.LGRIP2, T_R5 to Btn.RGRIP2,
        T_L3 to Btn.STICK_CLICK, T_R3 to Btn.RSTICK_CLICK,
        T_DPAD_UP to Btn.DPAD_UP, T_DPAD_DOWN to Btn.DPAD_DOWN, T_DPAD_LEFT to Btn.DPAD_LEFT, T_DPAD_RIGHT to Btn.DPAD_RIGHT,
        T_LT_CLICK to Btn.LT_CLICK, T_RT_CLICK to Btn.RT_CLICK,
        T_LPAD_CLICK to Btn.LPAD_CLICK, T_RPAD_CLICK to Btn.RPAD_CLICK,
        T_LPAD_TOUCH to Btn.LPAD_TOUCH, T_RPAD_TOUCH to Btn.RPAD_TOUCH,
        T_LSTICK_TOUCH to Btn.LSTICK_TOUCH, T_RSTICK_TOUCH to Btn.RSTICK_TOUCH,
        T_LGRIP_TOUCH to Btn.LGRIP_TOUCH, T_RGRIP_TOUCH to Btn.RGRIP_TOUCH,
    )

    fun translate(wire: Int): Int {
        var out = 0
        for ((w, b) in wireToBtn) if (wire and w != 0) out = out or b.bit
        return out
    }

    private fun s16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toInt()
    private fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    private fun u32(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8) or (u8(b, o + 2) shl 16) or (u8(b, o + 3) shl 24)

    enum class Result { NONE, STATE, BATTERY, CONNECTED, DISCONNECTED }

    /**
     * Parses one input report starting with its report id at index 0.
     * BLE notifications don't carry the id; the caller prepends it.
     */
    fun parse(b: ByteArray, len: Int, state: RawState): Result {
        if (len < 2) return Result.NONE
        when (u8(b, 0)) {
            ID_STATE, ID_STATE_BLE, ID_STATE_TIMESTAMP -> {
                // TritonMTUNoQuat_t / TritonMTUNoQuat32TS_t after the report id:
                // u8 seq, u32 buttons, s16 triggers L/R, s16 sticks LX LY RX RY, [u16 pad timestamp],
                // s16 left pad x/y, u16 pressure, s16 right pad x/y, u16 pressure, IMU.
                val ts = u8(b, 0) == ID_STATE_TIMESTAMP
                val padBase = if (ts) 20 else 18
                val imuAccel = 34
                if (len < imuAccel + 12) return Result.NONE
                state.buttons = translate(u32(b, 2))
                // Triggers are 0..32767.
                state.lt = (s16(b, 6).coerceAtLeast(0) shr 7).coerceAtMost(255)
                state.rt = (s16(b, 8).coerceAtLeast(0) shr 7).coerceAtMost(255)
                state.stickX = s16(b, 10); state.stickY = s16(b, 12)
                state.rstickX = s16(b, 14); state.rstickY = s16(b, 16)
                if (state.pressed(Btn.LPAD_TOUCH)) { state.lpadX = s16(b, padBase); state.lpadY = s16(b, padBase + 2) }
                else { state.lpadX = 0; state.lpadY = 0 }
                if (state.pressed(Btn.RPAD_TOUCH)) { state.rpadX = s16(b, padBase + 6); state.rpadY = s16(b, padBase + 8) }
                else { state.rpadX = 0; state.rpadY = 0 }
                // IMU: timestamp (u32, or u16 in the timestamp variant), then accel xyz, gyro xyz.
                // Both variants end up with accel at offset 34 after the report id.
                state.accelX = s16(b, imuAccel); state.accelY = s16(b, imuAccel + 2); state.accelZ = s16(b, imuAccel + 4)
                // Normalise to the 2015 convention (SDL: pitch = X, yaw = Z, roll = -Y).
                state.gyroX = s16(b, imuAccel + 6)
                state.gyroY = -s16(b, imuAccel + 8)
                state.gyroZ = s16(b, imuAccel + 10)
                return Result.STATE
            }
            ID_BATTERY -> {
                if (len >= 3) state.battery = u8(b, 2).coerceIn(0, 100)
                return Result.BATTERY
            }
            ID_WIRELESS, ID_WIRELESS_X -> return when (u8(b, 1)) {
                WIRELESS_CONNECT -> Result.CONNECTED
                WIRELESS_DISCONNECT -> Result.DISCONNECTED
                else -> Result.NONE
            }
        }
        return Result.NONE
    }

    // ---- Feature messages (without the report id). ----

    fun lizardOff() = Sc2015Protocol.settings(Sc2015Protocol.SETTING_LIZARD_MODE to 0)

    fun imu(enabled: Boolean) = Sc2015Protocol.settings(
        Sc2015Protocol.SETTING_IMU_MODE to if (enabled) (Sc2015Protocol.IMU_SEND_RAW_ACCEL or Sc2015Protocol.IMU_SEND_RAW_GYRO) else 0,
    )

    /** USB feature report: report id 1 followed by the message, 64 bytes total. */
    fun usbFeature(msg: ByteArray): ByteArray {
        val out = ByteArray(64)
        out[0] = FEATURE_REPORT_ID.toByte()
        System.arraycopy(msg, 0, out, 1, minOf(msg.size, 63))
        return out
    }

    // ---- Output reports (with the report id at index 0). ----

    /** Rumble motors, speeds 0..65535 (left = low frequency, right = high frequency). */
    fun rumble(left: Int, right: Int): ByteArray {
        val l = left.coerceIn(0, 0xFFFF)
        val r = right.coerceIn(0, 0xFFFF)
        // u8 id, u8 type, u16 intensity, {u16 speed, s8 gain} left, right.
        return byteArrayOf(
            ID_OUT_RUMBLE.toByte(), 0, 0, 0,
            l.toByte(), (l shr 8).toByte(), 0,
            r.toByte(), (r shr 8).toByte(), 0,
        )
    }

    fun hapticPulse(right: Boolean, onUs: Int, offUs: Int, count: Int): ByteArray {
        val on = onUs.coerceIn(0, 0xFFFF)
        val off = offUs.coerceIn(0, 0xFFFF)
        val n = count.coerceIn(0, 0xFFFF)
        return byteArrayOf(
            ID_OUT_HAPTIC_PULSE.toByte(), if (right) 0 else 1,
            on.toByte(), (on shr 8).toByte(), off.toByte(), (off shr 8).toByte(), n.toByte(), (n shr 8).toByte(),
        )
    }
}
