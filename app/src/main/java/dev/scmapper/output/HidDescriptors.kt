package dev.scmapper.output

/** HID report descriptors and report encoders for the virtual devices. */
object HidDescriptors {
    const val BUS_USB = 0x03
    const val BUS_BLUETOOTH = 0x05

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /**
     * How the virtual gamepad presents itself to Android. The identity decides which key layout
     * file Android applies and whether the kernel attaches a force-feedback (rumble) driver.
     */
    enum class GamepadIdentity(val title: String, val vendor: Int, val product: Int, val bus: Int, val deviceName: String) {
        /** Matches the kernel's hid-microsoft driver, which adds rumble support. */
        XBOX_ONE_BT("Xbox Wireless Controller (rumble support)", 0x045E, 0x02FD, BUS_BLUETOOTH, "Xbox Wireless Controller"),
        XBOX_360("Xbox 360 Controller (maximum compatibility)", 0x045E, 0x028E, BUS_USB, "Microsoft X-Box 360 pad"),
    }

    fun gamepad(identity: GamepadIdentity): ByteArray = when (identity) {
        GamepadIdentity.XBOX_ONE_BT -> bytes(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
            // Left stick X/Y, right stick Z/Rz: 16 bit unsigned.
            0x09, 0x01, 0xA1, 0x00,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
            0xC0,
            // Triggers: brake (LT) and accelerator (RT), 10 bit in 16 bit fields.
            0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
            *HAT_AND_BUTTONS,
            // View (AC Back) and Xbox (AC Home), as sent by the real Bluetooth pad.
            0x05, 0x0C, 0x0A, 0x24, 0x02, 0x0A, 0x23, 0x02,
            0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x02, 0x81, 0x02,
            0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
            // Rumble output report (hid-microsoft writes report 3).
            0x85, 0x03, 0x06, 0x00, 0xFF, 0x09, 0x01,
            0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x08, 0x91, 0x02,
            0xC0,
        )
        GamepadIdentity.XBOX_360 -> bytes(
            0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
            // Left stick X/Y, right stick Rx/Ry.
            0x09, 0x01, 0xA1, 0x00,
            0x09, 0x30, 0x09, 0x31, 0x09, 0x33, 0x09, 0x34,
            0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x75, 0x10, 0x95, 0x04, 0x81, 0x02,
            0xC0,
            // Triggers on Z (LT) and Rz (RT).
            0x09, 0x32, 0x09, 0x35,
            0x15, 0x00, 0x26, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x02, 0x81, 0x02,
            *HAT_AND_BUTTONS,
            0x75, 0x08, 0x95, 0x01, 0x81, 0x03,
            0xC0,
        )
    }

    private val HAT_AND_BUTTONS = intArrayOf(
        // Hat switch, 1..8 with null state.
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x66, 0x14, 0x00,
        0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x65, 0x00, 0x45, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        // 15 buttons -> BTN_A(304) .. BTN_THUMBR(318).
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
        0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
    )

    // HID button numbers (1 based) -> Linux BTN_* codes 304 + n - 1.
    const val HB_A = 1
    const val HB_B = 2
    const val HB_X = 4
    const val HB_Y = 5
    const val HB_L1 = 7
    const val HB_R1 = 8
    const val HB_SELECT = 11
    const val HB_START = 12
    const val HB_MODE = 13
    const val HB_THUMBL = 14
    const val HB_THUMBR = 15

    /** Encodes the gamepad input report. Axes in [-1, 1] (Y down positive), triggers in [0, 1]. */
    fun gamepadReport(
        identity: GamepadIdentity,
        lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float,
        hat: Int, hidButtons: Int, select: Boolean, mode: Boolean,
    ): ByteArray {
        val out = ByteArray(17)
        out[0] = 1
        fun put16(o: Int, v: Int) { out[o] = v.toByte(); out[o + 1] = (v shr 8).toByte() }
        fun axis(v: Float) = ((v.coerceIn(-1f, 1f) + 1f) * 32767.5f).toInt().coerceIn(0, 65535)
        fun trig(v: Float) = (v.coerceIn(0f, 1f) * 1023f).toInt()
        put16(1, axis(lx)); put16(3, axis(ly)); put16(5, axis(rx)); put16(7, axis(ry))
        put16(9, trig(lt)); put16(11, trig(rt))
        out[13] = (hat and 0x0F).toByte()
        var buttons = hidButtons
        var extra = 0
        if (identity == GamepadIdentity.XBOX_ONE_BT) {
            if (select) extra = extra or 1
            if (mode) extra = extra or 2
        } else {
            if (select) buttons = buttons or (1 shl (HB_SELECT - 1))
            if (mode) buttons = buttons or (1 shl (HB_MODE - 1))
        }
        put16(14, buttons)
        out[16] = extra.toByte()
        return out
    }

    /** Hat value from d-pad bits (1=N .. 8=NW, 0 = centered). */
    fun hat(up: Boolean, down: Boolean, left: Boolean, right: Boolean): Int {
        val v = (if (up) 1 else 0) - (if (down) 1 else 0)
        val h = (if (right) 1 else 0) - (if (left) 1 else 0)
        return when {
            v > 0 && h == 0 -> 1
            v > 0 && h > 0 -> 2
            v == 0 && h > 0 -> 3
            v < 0 && h > 0 -> 4
            v < 0 && h == 0 -> 5
            v < 0 && h < 0 -> 6
            v == 0 && h < 0 -> 7
            v > 0 && h < 0 -> 8
            else -> 0
        }
    }

    /** Keyboard (report 1, boot layout) plus consumer control (report 2, two 16 bit usages). */
    val KEYBOARD = bytes(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
        0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x91, 0x03,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
        0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, 0x02,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x02, 0x81, 0x00,
        0xC0,
    )

    fun keyboardReport(modifiers: Int, keys: Collection<Int>): ByteArray {
        val out = ByteArray(9)
        out[0] = 1
        out[1] = modifiers.toByte()
        keys.take(6).forEachIndexed { i, k -> out[3 + i] = k.toByte() }
        return out
    }

    fun consumerReport(usages: Collection<Int>): ByteArray {
        val out = ByteArray(5)
        out[0] = 2
        usages.take(2).forEachIndexed { i, u -> out[1 + i * 2] = u.toByte(); out[2 + i * 2] = (u shr 8).toByte() }
        return out
    }

    /** Mouse: 5 buttons, 16 bit relative X/Y, vertical wheel and horizontal pan. */
    val MOUSE = bytes(
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x05, 0x15, 0x00, 0x25, 0x01, 0x95, 0x05, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x03, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x16, 0x01, 0x80, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x02, 0x81, 0x06,
        0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0x05, 0x0C, 0x0A, 0x38, 0x02, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x01, 0x81, 0x06,
        0xC0, 0xC0,
    )

    fun mouseReport(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray {
        val x = dx.coerceIn(-32767, 32767)
        val y = dy.coerceIn(-32767, 32767)
        return byteArrayOf(
            buttons.toByte(), x.toByte(), (x shr 8).toByte(), y.toByte(), (y shr 8).toByte(),
            wheel.coerceIn(-127, 127).toByte(), pan.coerceIn(-127, 127).toByte(),
        )
    }
}
