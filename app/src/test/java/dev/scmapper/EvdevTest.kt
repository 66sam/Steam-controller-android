package dev.scmapper

import dev.scmapper.controller.AxisRole
import dev.scmapper.controller.Evdev
import dev.scmapper.controller.EvdevTranslator
import dev.scmapper.controller.GenericMapping
import dev.scmapper.controller.RawState
import dev.scmapper.mapping.Btn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvdevTest {
    // Shaped exactly like toolbox getevent -i output (codes as " %04x%c", ABS one per line).
    private val listing = """
add device 1: /dev/input/event3
  bus:      0005
  vendor    045e
  product   0b13
  version   0509
  name:     "Xbox Wireless Controller"
  location: "aa:bb:cc:dd:ee:ff"
  id:       "11:22:33:44:55:66"
  version:  1.0.1
  events:
    KEY (0001): 009e  00ac  0130  0131  0133  0134  0136  0137 
               013b  013d  013e 
    ABS (0003): 0000  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0
               0001  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0
               0002  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0
               0005  : value 32768, min 0, max 65535, fuzz 255, flat 4095, resolution 0
               0009  : value 0, min 0, max 1023, fuzz 3, flat 63, resolution 0
               000a  : value 0, min 0, max 1023, fuzz 3, flat 63, resolution 0
               0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
               0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
    MSC (0004): 0004 
    FF  (0015): 0050  0051  0058  0059  005a  0060 
  input props:
    <none>
add device 2: /dev/input/event4
  bus:      0005
  vendor    054c
  product   0ce6
  version   8100
  name:     "DualSense Wireless Controller"
  location: "x"
  id:       "a0:ab:51:00:00:01"
  version:  1.0.1
  events:
    KEY (0001): 0130* 0131  0133  0134  0136  0137  0138  0139 
               013a  013b  013c  013d  013e 
    ABS (0003): 0000  : value 128, min 0, max 255, fuzz 0, flat 0, resolution 0
               0001  : value 128, min 0, max 255, fuzz 0, flat 0, resolution 0
               0002  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
               0003  : value 128, min 0, max 255, fuzz 0, flat 0, resolution 0
               0004  : value 128, min 0, max 255, fuzz 0, flat 0, resolution 0
               0005  : value 0, min 0, max 255, fuzz 0, flat 0, resolution 0
               0010  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
               0011  : value 0, min -1, max 1, fuzz 0, flat 0, resolution 0
  input props:
    <none>
add device 3: /dev/input/event5
  bus:      0005
  vendor    054c
  product   0ce6
  version   8100
  name:     "DualSense Wireless Controller Motion Sensors"
  location: "x"
  id:       "a0:ab:51:00:00:01"
  version:  1.0.1
  events:
    ABS (0003): 0000  : value 0, min -32768, max 32767, fuzz 16, flat 0, resolution 8192
               0001  : value 0, min -32768, max 32767, fuzz 16, flat 0, resolution 8192
               0002  : value 0, min -32768, max 32767, fuzz 16, flat 0, resolution 8192
               0003  : value 0, min -2097152, max 2097152, fuzz 16, flat 0, resolution 1024
               0004  : value 0, min -2097152, max 2097152, fuzz 16, flat 0, resolution 1024
               0005  : value 0, min -2097152, max 2097152, fuzz 16, flat 0, resolution 1024
    MSC (0004): 0005 
  input props:
    INPUT_PROP_ACCELEROMETER
add device 4: /dev/input/event9
  bus:      0005
  vendor    045e
  product   02fd
  version   0100
  name:     "Xbox Wireless Controller"
  location: "scmapper/10"
  id:       ""
  version:  1.0.1
  events:
    KEY (0001): 0130  0131 
    ABS (0003): 0000  : value 32768, min 0, max 65535, fuzz 0, flat 0, resolution 0
               0001  : value 32768, min 0, max 65535, fuzz 0, flat 0, resolution 0
  input props:
    <none>
"""

    @Test
    fun parsesGeteventListing() {
        val devices = Evdev.parse(listing)
        assertEquals(4, devices.size)
        val xbox = devices[0]
        assertEquals("/dev/input/event3", xbox.path)
        assertEquals("Xbox Wireless Controller", xbox.name)
        assertEquals(0x045e, xbox.vendor)
        assertEquals(0x0b13, xbox.product)
        assertEquals(5, xbox.bus)
        assertTrue(0x13e in xbox.keys)
        assertTrue(158 in xbox.keys)
        assertFalse(0x50 in xbox.keys) // FF codes aren't keys
        assertEquals(1023, xbox.abs[Evdev.ABS_GAS]!!.max)
        assertTrue(xbox.isGamepad)
        val ds = devices[1]
        assertTrue(0x130 in ds.keys) // "0130*" (held) still parses
        assertTrue(ds.isGamepad)
        val motion = devices[2]
        assertTrue(motion.isMotionSensor)
        assertFalse(motion.isGamepad)
        assertEquals(1024, motion.abs[Evdev.ABS_RX]!!.resolution)
        assertEquals("scmapper/10", devices[3].location)
    }

    @Test
    fun xboxDefaultsAndTranslation() {
        val xbox = Evdev.parse(listing)[0]
        val m = GenericMapping.defaults(xbox)
        assertEquals(Btn.X, m.buttons[0x133]) // Xbox-style drivers: 0x133 is X
        assertEquals(Btn.Y, m.buttons[0x134])
        assertEquals(Btn.BACK, m.buttons[158])
        assertEquals(Btn.STEAM, m.buttons[172])
        assertEquals(AxisRole.RX, m.axes[Evdev.ABS_Z]) // with GAS/BRAKE, Z/RZ are the right stick
        assertEquals(AxisRole.LT, m.axes[Evdev.ABS_BRAKE])
        val t = EvdevTranslator(xbox, m)
        val s = RawState()
        t.apply(intArrayOf(1, 0x130, 1, 3, 0, 65535, 3, 1, 0, 3, Evdev.ABS_BRAKE, 1023, 3, Evdev.ABS_HAT0Y, -1), s)
        assertTrue(s.pressed(Btn.A))
        assertEquals(32767, s.stickX)
        assertEquals(32767, s.stickY) // evdev up (min) becomes positive
        assertEquals(255, s.lt)
        assertTrue(s.pressed(Btn.DPAD_UP))
        t.apply(intArrayOf(1, 0x130, 0, 3, Evdev.ABS_HAT0Y, 0), s)
        assertFalse(s.pressed(Btn.A))
        assertFalse(s.pressed(Btn.DPAD_UP))
    }

    @Test
    fun dualSenseDefaultsAndMotion() {
        val devices = Evdev.parse(listing)
        val ds = devices[1]
        val m = GenericMapping.defaults(ds)
        assertEquals(Btn.Y, m.buttons[0x133]) // spec-following driver: 0x133 is the top button
        assertEquals(Btn.X, m.buttons[0x134])
        assertEquals(AxisRole.RX, m.axes[Evdev.ABS_RX])
        assertEquals(AxisRole.LT, m.axes[Evdev.ABS_Z])
        val t = EvdevTranslator(ds, m)
        val s = RawState()
        t.apply(intArrayOf(3, Evdev.ABS_RX, 255, 3, Evdev.ABS_RZ, 255), s)
        assertEquals(32767, s.rstickX)
        assertEquals(255, s.rt)
        // 100 °/s around the yaw axis at 1024 units per °/s.
        t.applyMotion(intArrayOf(3, Evdev.ABS_RY, 102400), devices[2], s)
        assertEquals(1638, s.gyroZ)
    }

    @Test
    fun mappingRoundTrip() {
        val m = GenericMapping()
        m.buttons[0x130] = Btn.B
        m.axes[2] = AxisRole.LT
        m.inverted += 2
        val back = GenericMapping.fromJson(m.toJson())
        assertEquals(Btn.B, back.buttons[0x130])
        assertEquals(AxisRole.LT, back.axes[2])
        assertTrue(2 in back.inverted)
    }
}
