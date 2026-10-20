package dev.scmapper

import dev.scmapper.controller.RawState
import dev.scmapper.controller.SteamProtocol
import dev.scmapper.output.HidDescriptors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun usbState(buttons: Int, x: Int, y: Int, rx: Int = 0, ry: Int = 0, lt: Int = 0): ByteArray {
        val b = ByteArray(64)
        b[0] = 1; b[2] = SteamProtocol.REPORT_STATE.toByte(); b[3] = 60
        b[8] = buttons.toByte(); b[9] = (buttons shr 8).toByte(); b[10] = (buttons shr 16).toByte()
        b[11] = lt.toByte()
        fun s16(o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
        s16(16, x); s16(18, y); s16(20, rx); s16(22, ry)
        s16(30, 100); s16(32, -200); s16(34, 300)
        return b
    }

    @Test
    fun usbStickAndButtons() {
        val s = RawState()
        val type = SteamProtocol.parseUsb(usbState(SteamProtocol.B_A or SteamProtocol.B_RGRIP, 1000, -2000, lt = 200), 64, s)
        assertEquals(SteamProtocol.REPORT_STATE, type)
        assertTrue(s.pressed(SteamProtocol.B_A))
        assertTrue(s.pressed(SteamProtocol.B_RGRIP))
        assertEquals(1000, s.stickX)
        assertEquals(-2000, s.stickY)
        assertEquals(0, s.lpadX)
        assertEquals(200, s.lt)
        assertEquals(-200, s.gyroY)
    }

    @Test
    fun usbLeftPadAndStickMultiplexing() {
        val s = RawState()
        val both = SteamProtocol.B_LPAD_AND_STICK
        // Stick packet first, then pad packet while both are in use: both values survive.
        SteamProtocol.parseUsb(usbState(both, 5000, 6000), 64, s)
        SteamProtocol.parseUsb(usbState(both or SteamProtocol.B_LPAD_TOUCH, -7000, 8000), 64, s)
        assertEquals(5000, s.stickX)
        assertEquals(-7000, s.lpadX)
        assertTrue(s.pressed(SteamProtocol.B_LPAD_TOUCH))
        // Stick packet while both: pad still counts as touched.
        SteamProtocol.parseUsb(usbState(both, 100, 200), 64, s)
        assertTrue(s.pressed(SteamProtocol.B_LPAD_TOUCH))
        assertEquals(-7000, s.lpadX)
        // Finger lifted, only the stick remains.
        SteamProtocol.parseUsb(usbState(0, 300, 400), 64, s)
        assertFalse(s.pressed(SteamProtocol.B_LPAD_TOUCH))
        assertEquals(0, s.lpadX)
        assertEquals(300, s.stickX)
    }

    @Test
    fun bleChunks() {
        val s = RawState()
        // Report type 4 with buttons, triggers and right pad chunks.
        val mask = 0x10 or 0x20 or 0x200
        val p = byteArrayOf(
            (0x04 or (mask and 0xF0)).toByte(), (mask shr 8).toByte(),
            0x80.toByte(), 0x00, 0x10, // A + right pad touch
            0x11, 0x22, // triggers
            0x10, 0x27, 0xF0.toByte(), 0xD8.toByte(), // right pad 10000, -10000
        )
        assertTrue(SteamProtocol.parseBle(p, s))
        assertTrue(s.pressed(SteamProtocol.B_A))
        assertEquals(0x11, s.lt)
        assertEquals(0x22, s.rt)
        assertEquals(10000, s.rpadX)
        assertEquals(-10000, s.rpadY)
        // With a leading HID report id.
        val s2 = RawState()
        assertTrue(SteamProtocol.parseBle(byteArrayOf(0x03) + p, s2))
        assertEquals(10000, s2.rpadX)
    }

    @Test
    fun bleSegmentation() {
        val msg = SteamProtocol.configure(gyro = true, ble = true)
        val segs = SteamProtocol.bleSegments(msg)
        assertEquals(if (msg.size > 18) 2 else 1, segs.size)
        for ((i, seg) in segs.withIndex()) {
            assertEquals(19, seg.size)
            assertEquals(0x80 or i or (if (i == segs.size - 1) 0x40 else 0), seg[0].toInt() and 0xFF)
        }
        val joined = segs.flatMap { it.drop(1) }.take(msg.size).toByteArray()
        assertArrayEquals(msg, joined)
    }

    @Test
    fun hapticPulseLayout() {
        val m = SteamProtocol.hapticPulse(right = false, onUs = 0x1234, offUs = 0x10, count = 3)
        assertArrayEquals(byteArrayOf(0x8F.toByte(), 7, 1, 0x34, 0x12, 0x10, 0, 3, 0), m)
    }

    @Test
    fun gamepadReport() {
        val r = HidDescriptors.gamepadReport(
            HidDescriptors.GamepadIdentity.XBOX_ONE_BT, 0f, 0f, 1f, -1f, 1f, 0f,
            hat = 3, hidButtons = 1, select = true, mode = false,
        )
        assertEquals(17, r.size)
        assertEquals(1, r[0].toInt())
        assertEquals(0xFF.toByte(), r[5]); assertEquals(0xFF.toByte(), r[6]) // rx = max
        assertEquals(0, r[7].toInt()); assertEquals(0, r[8].toInt()) // ry = min
        assertEquals(0xFF.toByte(), r[9]); assertEquals(0x03.toByte(), r[10]) // lt = 1023
        assertEquals(3, r[13].toInt())
        assertEquals(1, r[14].toInt())
        assertEquals(1, r[16].toInt()) // AC Back
        assertEquals(1, HidDescriptors.hat(true, false, false, false))
        assertEquals(8, HidDescriptors.hat(true, false, true, false))
        assertEquals(0, HidDescriptors.hat(false, false, false, false))
    }
}
