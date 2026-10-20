package dev.scmapper

import dev.scmapper.controller.RawState
import dev.scmapper.controller.Sc2015Protocol
import dev.scmapper.controller.Sc2026Protocol
import dev.scmapper.mapping.Btn
import dev.scmapper.output.HidDescriptors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ProtocolTest {
    private fun s16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }

    private fun usbState(wire: Int, x: Int, y: Int, rx: Int = 0, ry: Int = 0, lt: Int = 0): ByteArray {
        val b = ByteArray(64)
        b[0] = 1; b[2] = Sc2015Protocol.REPORT_STATE.toByte(); b[3] = 60
        b[8] = wire.toByte(); b[9] = (wire shr 8).toByte(); b[10] = (wire shr 16).toByte()
        b[11] = lt.toByte()
        s16(b, 16, x); s16(b, 18, y); s16(b, 20, rx); s16(b, 22, ry)
        s16(b, 28, 11); s16(b, 30, 12); s16(b, 32, 13) // accel
        s16(b, 34, 100); s16(b, 36, -200); s16(b, 38, 300) // gyro
        return b
    }

    /** The 2015 driver rotates pads by ∓15° like Steam. */
    private fun rotated(x: Int, y: Int, deg: Double): Pair<Int, Int> {
        val r = Math.toRadians(deg)
        return (cos(r) * x - sin(r) * y).toInt() to (sin(r) * x + cos(r) * y).toInt()
    }

    @Test
    fun usb2015StickButtonsAndImu() {
        val s = RawState()
        val type = Sc2015Protocol.parseUsb(usbState(Sc2015Protocol.W_A or Sc2015Protocol.W_RGRIP, 1000, -2000, lt = 200), 64, s)
        assertEquals(Sc2015Protocol.REPORT_STATE, type)
        assertTrue(s.pressed(Btn.A))
        assertTrue(s.pressed(Btn.RGRIP))
        assertFalse(s.pressed(Btn.B))
        assertEquals(1000, s.stickX)
        assertEquals(-2000, s.stickY)
        assertEquals(0, s.lpadX)
        assertEquals(200, s.lt)
        assertEquals(11, s.accelX)
        assertEquals(100, s.gyroX)
        assertEquals(-200, s.gyroY)
        assertEquals(300, s.gyroZ)
    }

    @Test
    fun usb2015LeftPadAndStickMultiplexing() {
        val s = RawState()
        val both = Sc2015Protocol.W_LPAD_AND_STICK
        // Stick packet first, then pad packet while both are in use: both values survive.
        Sc2015Protocol.parseUsb(usbState(both, 5000, 6000), 64, s)
        Sc2015Protocol.parseUsb(usbState(both or Sc2015Protocol.W_LPAD_TOUCH, -7000, 8000), 64, s)
        assertEquals(5000, s.stickX)
        val (ex, ey) = rotated(-7000, 8000, -15.0)
        assertEquals(ex.toDouble(), s.lpadX.toDouble(), 2.0)
        assertEquals(ey.toDouble(), s.lpadY.toDouble(), 2.0)
        assertTrue(s.pressed(Btn.LPAD_TOUCH))
        // Stick packet while both: pad still counts as touched.
        Sc2015Protocol.parseUsb(usbState(both, 100, 200), 64, s)
        assertTrue(s.pressed(Btn.LPAD_TOUCH))
        assertEquals(ex.toDouble(), s.lpadX.toDouble(), 2.0)
        // Finger lifted, only the stick remains.
        Sc2015Protocol.parseUsb(usbState(0, 300, 400), 64, s)
        assertFalse(s.pressed(Btn.LPAD_TOUCH))
        assertEquals(0, s.lpadX)
        assertEquals(300, s.stickX)
    }

    @Test
    fun ble2015Chunks() {
        val s = RawState()
        // Report type 4 with buttons, triggers and right pad chunks.
        val mask = 0x10 or 0x20 or 0x200
        val p = byteArrayOf(
            (0x04 or (mask and 0xF0)).toByte(), (mask shr 8).toByte(),
            0x80.toByte(), 0x00, 0x10, // A + right pad touch
            0x11, 0x22, // triggers
            0x10, 0x27, 0x00, 0x00, // right pad 10000, 0
        )
        assertTrue(Sc2015Protocol.parseBle(p, s))
        assertTrue(s.pressed(Btn.A))
        assertTrue(s.pressed(Btn.RPAD_TOUCH))
        assertEquals(0x11, s.lt)
        assertEquals(0x22, s.rt)
        val (ex, ey) = rotated(10000, 0, 15.0)
        assertEquals(ex.toDouble(), s.rpadX.toDouble(), 2.0)
        assertEquals(ey.toDouble(), s.rpadY.toDouble(), 2.0)
        // With a leading HID report id.
        val s2 = RawState()
        assertTrue(Sc2015Protocol.parseBle(byteArrayOf(0x03) + p, s2))
        assertEquals(s.rpadX, s2.rpadX)
    }

    @Test
    fun ble2015Segmentation() {
        val msg = Sc2015Protocol.configure(gyro = true, ble = true)
        val segs = Sc2015Protocol.bleSegments(msg)
        assertEquals(if (msg.size > 18) 2 else 1, segs.size)
        for ((i, seg) in segs.withIndex()) {
            assertEquals(19, seg.size)
            assertEquals(0x80 or i or (if (i == segs.size - 1) 0x40 else 0), seg[0].toInt() and 0xFF)
        }
        val joined = segs.flatMap { it.drop(1) }.take(msg.size).toByteArray()
        assertArrayEquals(msg, joined)
    }

    @Test
    fun haptic2015Layout() {
        val m = Sc2015Protocol.hapticPulse(right = false, onUs = 0x1234, offUs = 0x10, count = 3)
        assertArrayEquals(byteArrayOf(0x8F.toByte(), 7, 1, 0x34, 0x12, 0x10, 0, 3, 0), m)
    }

    private fun tritonState(id: Int, buttons: Int): ByteArray {
        val b = ByteArray(64)
        b[0] = id.toByte()
        b[2] = buttons.toByte(); b[3] = (buttons shr 8).toByte(); b[4] = (buttons shr 16).toByte(); b[5] = (buttons shr 24).toByte()
        s16(b, 6, 32767); s16(b, 8, 16384) // triggers
        s16(b, 10, 1000); s16(b, 12, -1000); s16(b, 14, 2000); s16(b, 16, -2000) // sticks
        val pad = if (id == Sc2026Protocol.ID_STATE_TIMESTAMP) 20 else 18
        s16(b, pad, 3000); s16(b, pad + 2, -3000); s16(b, pad + 6, 4000); s16(b, pad + 8, -4000)
        s16(b, 34, 1); s16(b, 36, 2); s16(b, 38, 3) // accel
        s16(b, 40, 10); s16(b, 42, 20); s16(b, 44, 30) // gyro
        return b
    }

    @Test
    fun usb2026StateReports() {
        // A (0x1) + R4 (0x80) + dpad up (0x2000) + left pad touch (0x2000000) + right stick touch (0x100000).
        val buttons = 0x1 or 0x80 or 0x2000 or 0x2000000 or 0x100000
        for (id in listOf(Sc2026Protocol.ID_STATE, Sc2026Protocol.ID_STATE_BLE, Sc2026Protocol.ID_STATE_TIMESTAMP)) {
            val s = RawState()
            assertEquals(Sc2026Protocol.Result.STATE, Sc2026Protocol.parse(tritonState(id, buttons), 64, s))
            assertTrue(s.pressed(Btn.A))
            assertTrue(s.pressed(Btn.RGRIP))
            assertTrue(s.pressed(Btn.DPAD_UP))
            assertTrue(s.pressed(Btn.LPAD_TOUCH))
            assertTrue(s.pressed(Btn.RSTICK_TOUCH))
            assertFalse(s.pressed(Btn.RPAD_TOUCH))
            assertEquals(255, s.lt)
            assertEquals(128, s.rt)
            assertEquals(1000, s.stickX); assertEquals(-1000, s.stickY)
            assertEquals(2000, s.rstickX); assertEquals(-2000, s.rstickY)
            assertEquals(3000, s.lpadX); assertEquals(-3000, s.lpadY)
            assertEquals(0, s.rpadX) // not touched
            assertEquals(1, s.accelX)
            assertEquals(10, s.gyroX)
            assertEquals(-20, s.gyroY) // roll is flipped to match the 2015 convention
            assertEquals(30, s.gyroZ)
        }
    }

    @Test
    fun wireless2026AndBattery() {
        val s = RawState()
        assertEquals(Sc2026Protocol.Result.CONNECTED, Sc2026Protocol.parse(byteArrayOf(0x79, 2), 2, s))
        assertEquals(Sc2026Protocol.Result.DISCONNECTED, Sc2026Protocol.parse(byteArrayOf(0x46, 1), 2, s))
        assertEquals(Sc2026Protocol.Result.BATTERY, Sc2026Protocol.parse(byteArrayOf(0x43, 1, 77), 3, s))
        assertEquals(77, s.battery)
    }

    @Test
    fun output2026Reports() {
        assertArrayEquals(
            byteArrayOf(0x80.toByte(), 0, 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0x34, 0x12, 0),
            Sc2026Protocol.rumble(65535, 0x1234),
        )
        assertArrayEquals(
            byteArrayOf(0x81.toByte(), 1, 0x10, 0, 0x20, 0, 2, 0),
            Sc2026Protocol.hapticPulse(right = false, onUs = 0x10, offUs = 0x20, count = 2),
        )
        val f = Sc2026Protocol.usbFeature(Sc2026Protocol.lizardOff())
        assertEquals(64, f.size)
        assertArrayEquals(byteArrayOf(1, 0x87.toByte(), 3, 9, 0, 0), f.copyOf(6))
        assertEquals("100f6cb5-1735-4313-b402-38567131e5f3", Sc2026Protocol.bleOutputCharFor(0x80))
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
