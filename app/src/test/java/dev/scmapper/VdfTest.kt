package dev.scmapper

import dev.scmapper.mapping.Action
import dev.scmapper.mapping.Btn
import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.GyroMode
import dev.scmapper.mapping.Keys
import dev.scmapper.mapping.MouseOut
import dev.scmapper.mapping.Presets
import dev.scmapper.mapping.SteamInputVdf
import dev.scmapper.mapping.StickTarget
import dev.scmapper.mapping.SurfaceMode
import dev.scmapper.mapping.TriggerTarget
import dev.scmapper.mapping.Vdf
import dev.scmapper.mapping.WheelOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VdfTest {
    private fun input(name: String, vararg bindings: String, long: String? = null) = """
        "$name"
        {
            "activators"
            {
                "Full_Press"
                {
                    "bindings"
                    {
${bindings.joinToString("\n") { "                        \"binding\"  \"$it, , \"" }}
                    }
                }
${if (long != null) "\"Long_Press\" { \"bindings\" { \"binding\" \"$long, , \" } }" else ""}
            }
            "disabled_activators" { }
        }
    """

    private val sample = """
"controller_mappings"
{
    "version"  "3"
    "title"    "Test config"
    "controller_type" "controller_steamcontroller_gordon"
    "group"
    {
        "id" "0"
        "mode" "four_buttons"
        "inputs"
        {
            ${input("button_a", "xinput_button A")}
            ${input("button_b", "key_press LEFT_CONTROL", "key_press P")}
            ${input("button_x", "mouse_wheel SCROLL_UP")}
            "button_y"
            {
                "activators"
                {
                    "Double_Press" { "bindings" { "binding" "key_press SPACE, , " } }
                }
            }
        }
    }
    "group"
    {
        "id" "1"
        "mode" "switches"
        "inputs"
        {
            ${input("button_escape", "xinput_button START")}
            ${input("left_bumper", "xinput_button SHOULDER_LEFT", long = "key_press F5")}
            ${input("button_back_left", "key_press LEFT_SHIFT")}
        }
    }
    "group"
    {
        "id" "2"
        "mode" "dpad"
        "inputs"
        {
            ${input("dpad_north", "key_press W")}
            ${input("dpad_south", "key_press S")}
            ${input("dpad_west", "key_press A")}
            ${input("dpad_east", "key_press D")}
        }
        "settings" { "requires_click" "0" }
    }
    "group"
    {
        "id" "3"
        "mode" "absolute_mouse"
        "inputs" { ${input("click", "mouse_button LEFT")} }
    }
    "group"
    {
        "id" "4"
        "mode" "trigger"
        "inputs" { ${input("click", "mouse_button RIGHT")} }
        "settings" { "output_trigger" "1" }
    }
    "group"
    {
        "id" "5"
        "mode" "joystick_move"
        "inputs" { ${input("click", "xinput_button JOYSTICK_LEFT")} }
    }
    "group"
    {
        "id" "6"
        "mode" "absolute_mouse"
    }
    "group"
    {
        "id" "7"
        "mode" "dpad"
    }
    "preset"
    {
        "id" "0"
        "name" "Default"
        "group_source_bindings"
        {
            "0" "button_diamond active"
            "1" "switch active"
            "2" "left_trackpad active"
            "3" "right_trackpad active"
            "4" "left_trigger active"
            "5" "joystick active"
            "6" "gyro active"
            "7" "right_trackpad active modeshift"
        }
    }
}
"""

    @Test
    fun parsesDuplicateKeysAndEscapes() {
        val n = Vdf.parse("\"a\" { \"k\" \"1\" \"k\" \"2\" \"q\" \"say \\\"hi\\\"\" // comment\n }")
        val a = n.child("a")!!
        assertEquals(listOf("1", "2"), a.strings("k"))
        assertEquals("say \"hi\"", a.str("q"))
    }

    @Test
    fun importsSteamInputConfig() {
        val r = SteamInputVdf.import(sample, "fallback")
        val p = r.profile
        assertEquals("Test config", p.name)
        assertEquals(Action.gp(GamepadOut.A), p.binding(Btn.A).press)
        assertEquals(Action.key(19, Keys.MOD_CTRL), p.binding(Btn.B).press) // Ctrl+P
        assertEquals(Action.wheel(WheelOut.UP), p.binding(Btn.X).press)
        assertTrue(p.binding(Btn.Y).press.isNone)
        assertEquals(Action.gp(GamepadOut.START), p.binding(Btn.START).press)
        assertEquals(Action.gp(GamepadOut.LB), p.binding(Btn.LB).press)
        assertEquals(Action.key(62), p.binding(Btn.LB).hold) // F5
        assertEquals(Action.key(225), p.binding(Btn.LGRIP).press)
        assertEquals(SurfaceMode.DIRECTIONAL, p.leftPad.mode)
        assertFalse(p.leftPad.requireClick)
        assertEquals(Action.key(26), p.leftPad.dirActions[0]) // W
        assertEquals(SurfaceMode.MOUSE, p.rightPad.mode) // the mode-shift group is ignored
        assertEquals(Action.mouse(MouseOut.LEFT), p.rightPad.clickAction)
        assertEquals(TriggerTarget.LEFT, p.leftTrigger.analog)
        assertEquals(Action.mouse(MouseOut.RIGHT), p.leftTrigger.fullAction)
        assertEquals(SurfaceMode.JOYSTICK, p.stick.mode)
        assertEquals(StickTarget.LEFT, p.stick.target)
        assertEquals(Action.gp(GamepadOut.L3), p.binding(Btn.STICK_CLICK).press)
        assertEquals(GyroMode.MOUSE, p.gyro.mode)
        assertTrue(r.notes.any { "Double Press" in it })
        assertTrue(r.notes.any { "Mode shifts" in it })
    }

    @Test
    fun exportThenImportKeepsBindings() {
        for (preset in Presets.all()) {
            val text = SteamInputVdf.export(preset)
            val back = SteamInputVdf.import(text, "x").profile
            assertEquals(preset.name, back.name)
            for (b in listOf(Btn.A, Btn.B, Btn.X, Btn.Y, Btn.LB, Btn.RB, Btn.START, Btn.BACK, Btn.LGRIP, Btn.RGRIP)) {
                val a = preset.binding(b).press
                if (a.type == dev.scmapper.mapping.ActionType.MEDIA || a.type == dev.scmapper.mapping.ActionType.SPECIAL) continue
                assertEquals("${preset.name} $b", a, back.binding(b).press)
            }
            assertEquals(preset.rightPad.mode, back.rightPad.mode)
            assertEquals(preset.leftPad.mode, back.leftPad.mode)
            assertEquals(preset.stick.mode, back.stick.mode)
            assertEquals(preset.leftTrigger.analog, back.leftTrigger.analog)
            assertEquals(preset.gyro.mode, back.gyro.mode)
        }
    }
}
