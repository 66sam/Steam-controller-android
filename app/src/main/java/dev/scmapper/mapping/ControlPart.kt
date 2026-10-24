package dev.scmapper.mapping

import dev.scmapper.controller.RawState
import kotlin.math.hypot

/**
 * Something the user can pick in the visual mapper: a single button (bound with a
 * [ButtonBinding]) or a whole section (stick, trackpad, trigger, gyro) with its own settings.
 */
enum class ControlPart(val short: String, val btn: Btn? = null) {
    A("A", Btn.A), B("B", Btn.B), X("X", Btn.X), Y("Y", Btn.Y),
    LB("LB", Btn.LB), RB("RB", Btn.RB),
    BACK("◀", Btn.BACK), START("▶", Btn.START), STEAM("Steam", Btn.STEAM), QAM("…", Btn.QAM),
    LGRIP("L4", Btn.LGRIP), RGRIP("R4", Btn.RGRIP), LGRIP2("L5", Btn.LGRIP2), RGRIP2("R5", Btn.RGRIP2),
    STICK_CLICK("L3", Btn.STICK_CLICK), RSTICK_CLICK("R3", Btn.RSTICK_CLICK),
    DPAD_UP("↑", Btn.DPAD_UP), DPAD_DOWN("↓", Btn.DPAD_DOWN), DPAD_LEFT("←", Btn.DPAD_LEFT), DPAD_RIGHT("→", Btn.DPAD_RIGHT),
    LT("LT"), RT("RT"), LSTICK("Left stick"), RSTICK("Right stick"), LPAD("Left pad"), RPAD("Right pad"), GYRO("Gyro");

    val label: String get() = btn?.label ?: short

    companion object {
        private val byBtn: Map<Btn, ControlPart> = buildMap {
            for (p in ControlPart.entries) p.btn?.let { put(it, p) }
            put(Btn.LT_CLICK, LT); put(Btn.RT_CLICK, RT)
            put(Btn.LPAD_CLICK, LPAD); put(Btn.LPAD_TOUCH, LPAD)
            put(Btn.RPAD_CLICK, RPAD); put(Btn.RPAD_TOUCH, RPAD)
            put(Btn.LSTICK_TOUCH, LSTICK); put(Btn.RSTICK_TOUCH, RSTICK)
        }

        /** Parts being used right now: pressed buttons, touched pads, pushed sticks, pulled triggers. */
        fun active(s: RawState): Set<ControlPart> {
            val out = HashSet<ControlPart>()
            for (b in Btn.entries) if (s.pressed(b)) byBtn[b]?.let { out += it }
            if (s.lt > 128) out += LT
            if (s.rt > 128) out += RT
            if (hypot(s.stickX.toFloat(), s.stickY.toFloat()) > 20000f) out += LSTICK
            if (hypot(s.rstickX.toFloat(), s.rstickY.toFloat()) > 20000f) out += RSTICK
            return out
        }
    }
}
