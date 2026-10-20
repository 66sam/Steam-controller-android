package dev.scmapper

import dev.scmapper.controller.RawState
import dev.scmapper.mapping.Action
import dev.scmapper.mapping.Btn
import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.Mapper
import dev.scmapper.mapping.MapperHost
import dev.scmapper.mapping.MenuNav
import dev.scmapper.mapping.Presets
import dev.scmapper.mapping.Profile
import dev.scmapper.mapping.Special
import dev.scmapper.mapping.SurfaceConfig
import dev.scmapper.mapping.SurfaceMode
import dev.scmapper.mapping.WheelOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapperTest {
    private class Host : MapperHost {
        val specials = ArrayList<Special>()
        var haptics = 0
        override fun haptic(right: Boolean, onUs: Int, offUs: Int, count: Int) { haptics++ }
        override fun rumble(low: Float, high: Float) {}
        override fun special(s: Special) { specials += s }
        override fun turnOffController() {}
        override fun calibrated() {}
        override val menuOpen = false
        override fun menuNavigate(nav: MenuNav) {}
    }

    private var now = 1000L
    private val host = Host()
    private val mapper = Mapper(host)
    private val s = RawState()

    private fun step(ms: Long = 8) {
        now += ms
        mapper.process(s, now, now * 1_000_000)
    }

    @Test
    fun gamepadPresetBasics() {
        mapper.profile = Presets.gamepad()
        s.buttons = Btn.A.bit
        step()
        assertTrue(mapper.frame.has(GamepadOut.A))
        s.buttons = 0
        step()
        assertFalse(mapper.frame.has(GamepadOut.A))

        // Right pad acts as the right stick.
        s.buttons = Btn.RPAD_TOUCH.bit
        s.rpadX = 32767; s.rpadY = 0
        step()
        assertEquals(1f, mapper.frame.rx, 0.01f)

        // Left pad click on the top edge is d-pad up.
        s.buttons = Btn.LPAD_TOUCH.bit or Btn.LPAD_CLICK.bit
        s.lpadX = 0; s.lpadY = 30000
        step()
        assertTrue(mapper.frame.has(GamepadOut.DPAD_UP))
        assertFalse(mapper.frame.has(GamepadOut.DPAD_LEFT))

        // Triggers are analog.
        s.buttons = 0; s.lt = 255
        step()
        assertEquals(1f, mapper.frame.lt, 0.01f)
    }

    @Test
    fun steamChordsSwitchProfilesAndSuppressButton() {
        mapper.profile = Presets.gamepad()
        s.buttons = Btn.STEAM.bit
        step()
        s.buttons = Btn.STEAM.bit or Btn.RB.bit
        step()
        assertEquals(listOf(Special.NEXT_PROFILE), host.specials)
        assertFalse(mapper.frame.has(GamepadOut.RB))
        // Releasing Steam with RB still held keeps RB suppressed; no Guide tap.
        s.buttons = Btn.RB.bit
        step()
        assertFalse(mapper.frame.has(GamepadOut.RB))
        assertFalse(mapper.frame.has(GamepadOut.GUIDE))
    }

    @Test
    fun steamTapFiresGuide() {
        mapper.profile = Presets.gamepad()
        s.buttons = Btn.STEAM.bit
        step()
        assertFalse(mapper.frame.has(GamepadOut.GUIDE))
        s.buttons = 0
        step()
        assertTrue(mapper.frame.has(GamepadOut.GUIDE))
        step(100)
        assertFalse(mapper.frame.has(GamepadOut.GUIDE))
    }

    @Test
    fun longPressAction() {
        val p = Profile("t")
        p.binding(Btn.LGRIP).press = Action.gp(GamepadOut.A)
        p.binding(Btn.LGRIP).hold = Action.gp(GamepadOut.B)
        mapper.profile = p
        s.buttons = Btn.LGRIP.bit
        step()
        assertFalse(mapper.frame.has(GamepadOut.A))
        step(500)
        assertTrue(mapper.frame.has(GamepadOut.B))
        s.buttons = 0
        step()
        assertFalse(mapper.frame.has(GamepadOut.A))
        // Short press taps A.
        s.buttons = Btn.LGRIP.bit
        step()
        s.buttons = 0
        step()
        assertTrue(mapper.frame.has(GamepadOut.A))
    }

    @Test
    fun gridZones() {
        val p = Profile("t")
        p.rightPad = SurfaceConfig(mode = SurfaceMode.GRID, rows = 2, cols = 2).apply {
            gridActions[0] = Action.gp(GamepadOut.X); gridActions[1] = Action.gp(GamepadOut.Y)
            gridActions[2] = Action.gp(GamepadOut.A); gridActions[3] = Action.gp(GamepadOut.B)
        }
        mapper.profile = p
        s.buttons = Btn.RPAD_TOUCH.bit
        s.rpadX = 20000; s.rpadY = -20000 // bottom right
        step()
        assertTrue(mapper.frame.has(GamepadOut.B))
        s.rpadX = -20000; s.rpadY = 20000 // top left
        step()
        assertTrue(mapper.frame.has(GamepadOut.X))
        assertTrue(host.haptics >= 2)
    }

    @Test
    fun padMouseAndScroll() {
        mapper.profile = Presets.keyboardMouse()
        s.buttons = Btn.RPAD_TOUCH.bit
        s.rpadX = 0; s.rpadY = 0
        step()
        s.rpadX = 3276
        step()
        step()
        assertTrue(mapper.frame.mouseDx > 10f)

        // Left pad: circular scrolling clockwise scrolls down.
        mapper.frame.wheel = 0
        s.buttons = Btn.LPAD_TOUCH.bit
        for (deg in 90 downTo -90 step 10) {
            val r = Math.toRadians(deg.toDouble())
            s.lpadX = (Math.cos(r) * 25000).toInt(); s.lpadY = (Math.sin(r) * 25000).toInt()
            step()
        }
        assertTrue("wheel=${mapper.frame.wheel}", mapper.frame.wheel < 0)
    }

    @Test
    fun wheelActionRepeats() {
        val p = Profile("t")
        p.binding(Btn.Y).press = Action.wheel(WheelOut.UP)
        mapper.profile = p
        s.buttons = Btn.Y.bit
        step()
        assertEquals(1, mapper.frame.wheel)
        step()
        assertEquals(1, mapper.frame.wheel)
        step(400)
        assertEquals(2, mapper.frame.wheel)
    }

    @Test
    fun rightStickAndDpadButtons2026() {
        mapper.profile = Presets.gamepad()
        s.rstickX = 0; s.rstickY = 32767
        s.buttons = Btn.DPAD_LEFT.bit or Btn.RSTICK_CLICK.bit
        step()
        assertEquals(1f, mapper.frame.ry, 0.02f)
        assertTrue(mapper.frame.has(GamepadOut.DPAD_LEFT))
        assertTrue(mapper.frame.has(GamepadOut.R3))
    }

    @Test
    fun presetsRoundTripThroughJson() {
        for (p in Presets.all()) {
            val back = Profile.fromJson(p.toJson())
            assertEquals(p.toJson().toString(), back.toJson().toString())
        }
        assertTrue(Presets.keyboardMouse().usesMouse())
        assertTrue(Presets.keyboardMouse().usesKeyboard())
        assertFalse(Presets.gamepad().usesKeyboard())
    }
}
