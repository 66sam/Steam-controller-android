package dev.scmapper.mapping

import dev.scmapper.controller.RawState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/** What the mapper needs from its surroundings. */
interface MapperHost {
    /** Plays a haptic pulse on one trackpad. Already scaled by the profile's strength. */
    fun haptic(right: Boolean, onUs: Int, offUs: Int, count: Int)
    /** Game rumble, each side 0..1, already scaled; called repeatedly while active, then with zeros. */
    fun rumble(low: Float, high: Float)
    fun special(s: Special)
    fun turnOffController()
    fun calibrated()
    val menuOpen: Boolean
    fun menuNavigate(nav: MenuNav)
}

enum class MenuNav { UP, DOWN, SELECT, CLOSE }

/**
 * Turns the raw state of one controller into output for the virtual devices, following a [Profile].
 * Not thread safe: call everything from the engine thread.
 */
class Mapper(private val host: MapperHost) {
    var profile: Profile = Presets.gamepad()
        set(value) {
            field = value
            resetRuntime()
        }

    /** Global gyro on/off, toggled with Steam + Y. */
    var gyroEnabled = true

    val frame = OutputFrame()

    private var prevButtons = 0
    private var lastNanos = 0L

    // Steam button chords.
    private var steamDownAt = 0L
    private var steamChordUsed = false
    private var steamHoldActive = false
    private var steamPowerOffFired = false
    private var suppressed = 0

    private val buttonRt = HashMap<Btn, ButtonRt>()
    private val stickRt = SurfaceRt()
    private val rstickRt = SurfaceRt()
    private val lpadRt = SurfaceRt()
    private val rpadRt = SurfaceRt()
    private var ltSoft = false
    private var rtSoft = false

    private val pulses = ArrayList<Pulse>()
    private val edgesNow = HashMap<String, Action>()
    private val edgeNext = HashMap<String, Long>()

    // Gyro.
    private var gyroToggled = false
    private var gyroBtnPrev = false
    private val bias = FloatArray(3)
    private var stillSince = 0L
    private val stillSum = FloatArray(3)
    private var stillCount = 0
    private var calibrateUntil = 0L
    private var calibrated = false

    // Rumble from the game: 0..1 per side.
    private var rumbleStrong = 0f
    private var rumbleWeak = 0f
    private var rumbleUntil = 0L
    private var nextRumble = 0L
    private var rumbleActive = false

    // Menu navigation edges.
    private var menuPrevDir = 0

    private class ButtonRt {
        var down = false
        var downAt = 0L
        var holdFired = false
    }

    private class SurfaceRt {
        var wasTouched = false
        var px = 0f
        var py = 0f
        var vx = 0f
        var vy = 0f
        var lastDx = 0f
        var lastDy = 0f
        var dist = 0f
        var zone = -1
        var angle = 0f
        var hasAngle = false
        var angleAcc = 0f
    }

    private class Pulse(val action: Action, val until: Long, val key: String)

    private fun resetRuntime() {
        buttonRt.clear()
        for (rt in listOf(stickRt, rstickRt, lpadRt, rpadRt)) {
            rt.wasTouched = false; rt.vx = 0f; rt.vy = 0f; rt.zone = -1; rt.hasAngle = false; rt.angleAcc = 0f
        }
        pulses.clear()
        edgeNext.clear()
        gyroToggled = false
        ltSoft = false; rtSoft = false
    }

    fun setRumble(strong: Float, weak: Float, nowMs: Long) {
        rumbleStrong = strong
        rumbleWeak = weak
        rumbleUntil = nowMs + 10_000
    }

    /** Starts a one second gyro calibration; keep the controller still. */
    fun calibrateGyro(nowMs: Long) {
        calibrateUntil = nowMs + 1000
        stillSum.fill(0f)
        stillCount = 0
    }

    /** Processes one step. [s] is the latest raw state; output lands in [frame]. */
    fun process(s: RawState, nowMs: Long, nowNanos: Long) {
        val dt = if (lastNanos == 0L) 0.004f else ((nowNanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = nowNanos
        frame.clearLevels()
        edgesNow.clear()

        updateGyroBias(s, nowMs)
        val buttons = handleSteamChords(s.buttons, nowMs)

        if (host.menuOpen) {
            handleMenu(s, buttons)
            prevButtons = s.buttons
            return
        }

        // Buttons.
        for (btn in Btn.entries) {
            if (!btn.bindable || btn == Btn.STEAM) continue
            val binding = profile.buttons[btn] ?: continue
            processButton(btn, binding, buttons and btn.bit != 0, nowMs)
        }
        processSteamButton(s.buttons, nowMs)

        // Triggers.
        ltSoft = processTrigger(profile.leftTrigger, s.lt, buttons and Btn.LT_CLICK.bit != 0, ltSoft, "lt")
        rtSoft = processTrigger(profile.rightTrigger, s.rt, buttons and Btn.RT_CLICK.bit != 0, rtSoft, "rt")

        // Sticks and pads.
        processSurface(profile.stick, stickRt, s.stickX / 32767f, s.stickY / 32767f, true, false, isStick = true, right = false, key = "stick", dt = dt, nowMs = nowMs)
        processSurface(profile.rightStick, rstickRt, s.rstickX / 32767f, s.rstickY / 32767f, true, false, isStick = true, right = true, key = "rstick", dt = dt, nowMs = nowMs)
        processSurface(
            profile.leftPad, lpadRt, s.lpadX / 32767f, s.lpadY / 32767f,
            s.pressed(Btn.LPAD_TOUCH), buttons and Btn.LPAD_CLICK.bit != 0,
            isStick = false, right = false, key = "lpad", dt = dt, nowMs = nowMs,
        )
        processSurface(
            profile.rightPad, rpadRt, s.rpadX / 32767f, s.rpadY / 32767f,
            s.pressed(Btn.RPAD_TOUCH), buttons and Btn.RPAD_CLICK.bit != 0,
            isStick = false, right = true, key = "rpad", dt = dt, nowMs = nowMs,
        )

        processGyro(s, dt)

        // Timed pulses (taps, scroll steps).
        val it = pulses.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (nowMs >= p.until) it.remove() else apply(p.action, p.key)
        }

        fireEdges(nowMs)
        clampSticks()
        processRumble(nowMs)
        prevButtons = s.buttons
    }

    // ---------------------------------------------------------------- Steam button

    /** Buttons that take part in Steam + button shortcuts (and are swallowed while Steam is held). */
    private val chordBits = Btn.entries.filter { it.bindable && it != Btn.STEAM }.fold(0) { acc, b -> acc or b.bit }

    /** Handles Steam + button shortcuts; returns the button bits left for normal mapping. */
    private fun handleSteamChords(raw: Int, nowMs: Long): Int {
        val steam = raw and Btn.STEAM.bit != 0
        val wasSteam = prevButtons and Btn.STEAM.bit != 0
        if (steam && !wasSteam) {
            steamDownAt = nowMs
            steamChordUsed = false
            steamPowerOffFired = false
        }
        if (steam) {
            val newly = raw and prevButtons.inv() and chordBits
            if (newly != 0) {
                steamChordUsed = true
                suppressed = suppressed or newly
                for (b in Btn.entries) if (newly and b.bit != 0) chord(b, nowMs)
            }
            if (!steamChordUsed && !steamPowerOffFired && nowMs - steamDownAt > POWER_OFF_MS) {
                steamPowerOffFired = true
                host.turnOffController()
            }
        }
        suppressed = suppressed and raw
        return raw and suppressed.inv() and Btn.STEAM.bit.inv()
    }

    private fun chord(b: Btn, nowMs: Long) {
        when (b) {
            Btn.RB -> host.special(Special.NEXT_PROFILE)
            Btn.LB -> host.special(Special.PREV_PROFILE)
            Btn.BACK, Btn.START, Btn.QAM -> host.special(Special.PROFILE_MENU)
            Btn.Y -> host.special(Special.TOGGLE_GYRO)
            Btn.X -> calibrateGyro(nowMs)
            else -> {}
        }
    }

    /** The Steam button is a modifier: its own binding fires on tap (or long hold) without a chord. */
    private fun processSteamButton(raw: Int, nowMs: Long) {
        val binding = profile.buttons[Btn.STEAM] ?: return
        val steam = raw and Btn.STEAM.bit != 0
        val wasSteam = prevButtons and Btn.STEAM.bit != 0
        if (steam && !steamChordUsed && !binding.hold.isNone && nowMs - steamDownAt >= HOLD_MS) {
            steamHoldActive = true
            apply(binding.hold, "steam:hold")
        }
        if (!steam && wasSteam) {
            if (!steamChordUsed && !steamHoldActive && !steamPowerOffFired) tap(binding.press, "steam", nowMs)
            steamHoldActive = false
        }
    }

    // ---------------------------------------------------------------- buttons

    private fun processButton(btn: Btn, binding: ButtonBinding, pressed: Boolean, nowMs: Long) {
        val rt = buttonRt.getOrPut(btn) { ButtonRt() }
        val key = btn.name
        if (pressed && !rt.down) {
            rt.down = true
            rt.downAt = nowMs
            rt.holdFired = false
        }
        if (binding.hold.isNone) {
            if (pressed) {
                val on = !binding.turbo || ((nowMs - rt.downAt) / TURBO_HALF_PERIOD_MS) % 2 == 0L
                if (on) apply(binding.press, key)
            }
        } else {
            if (pressed && nowMs - rt.downAt >= HOLD_MS) {
                rt.holdFired = true
                apply(binding.hold, "$key:hold")
            }
            if (!pressed && rt.down && !rt.holdFired) tap(binding.press, key, nowMs)
        }
        if (!pressed) rt.down = false
    }

    private fun tap(a: Action, key: String, nowMs: Long) {
        if (a.isNone) return
        pulses += Pulse(a, nowMs + TAP_MS, "$key:tap")
    }

    // ---------------------------------------------------------------- triggers

    private fun processTrigger(cfg: TriggerConfig, raw: Int, click: Boolean, wasSoft: Boolean, key: String): Boolean {
        var v = raw / 255f
        if (v < cfg.deadzone) v = 0f else v = ((v - cfg.deadzone) / (1f - cfg.deadzone)).coerceAtMost(1f)
        when (cfg.analog) {
            TriggerTarget.LEFT -> frame.lt = max(frame.lt, v)
            TriggerTarget.RIGHT -> frame.rt = max(frame.rt, v)
            TriggerTarget.NONE -> {}
        }
        val soft = if (wasSoft) v > cfg.softThreshold - 0.05f else v >= cfg.softThreshold
        if (soft) apply(cfg.softAction, "$key:soft")
        if (click) apply(cfg.fullAction, "$key:full")
        return soft
    }

    // ---------------------------------------------------------------- stick and pads

    private fun processSurface(
        cfg: SurfaceConfig, rt: SurfaceRt, rawX: Float, rawY: Float, touched: Boolean, clicked: Boolean,
        isStick: Boolean, right: Boolean, key: String, dt: Float, nowMs: Long,
    ) {
        var x = rawX
        var y = rawY
        if (cfg.rotationDeg != 0f) {
            val r = Math.toRadians(cfg.rotationDeg.toDouble())
            val c = cos(r).toFloat()
            val s = sin(r).toFloat()
            x = rawX * c - rawY * s
            y = rawX * s + rawY * c
        }
        val mag = hypot(x, y)
        val zoneMode = cfg.mode == SurfaceMode.JOYSTICK || cfg.mode == SurfaceMode.DIRECTIONAL || cfg.mode == SurfaceMode.GRID
        val active = touched && (isStick || !cfg.requireClick || clicked)
        val hapticsOn = cfg.haptics && !isStick

        when (cfg.mode) {
            SurfaceMode.NONE -> {}
            SurfaceMode.JOYSTICK -> if (active && mag > cfg.deadzone) {
                val scaled = min(1f, (mag - cfg.deadzone) / (1f - cfg.deadzone) * cfg.sensitivity)
                val ox = x / mag * scaled
                val oy = (y / mag * scaled) * if (cfg.invertY) -1f else 1f
                addStick(cfg.target, ox, oy)
            }
            SurfaceMode.DIRECTIONAL -> {
                var zone = -1
                if (active) {
                    if (mag < cfg.deadzone) {
                        if (!cfg.centerAction.isNone) { apply(cfg.centerAction, "$key:center"); zone = 16 }
                    } else {
                        val dirs = directions(x, y, cfg.eightWay)
                        for (i in 0 until 4) if (dirs and (1 shl i) != 0) apply(cfg.dirActions[i], "$key:dir$i")
                        zone = dirs
                    }
                }
                zoneHaptic(rt, zone, hapticsOn, right)
            }
            SurfaceMode.GRID -> {
                var zone = -1
                if (active) {
                    val col = floor((x + 1f) / 2f * cfg.cols).toInt().coerceIn(0, cfg.cols - 1)
                    val row = floor((1f - y) / 2f * cfg.rows).toInt().coerceIn(0, cfg.rows - 1)
                    zone = row * cfg.cols + col
                    apply(cfg.gridActions[zone], "$key:grid$zone")
                }
                zoneHaptic(rt, zone, hapticsOn, right)
            }
            SurfaceMode.MOUSE -> if (isStick) stickMouse(cfg, x, y, mag, dt) else padMouse(cfg, rt, x, y, touched, dt, right)
            SurfaceMode.SCROLL -> scrollWheel(cfg, rt, x, y, mag, touched, key, nowMs, hapticsOn, right)
        }

        if (!isStick && clicked && !(zoneMode && cfg.requireClick)) apply(cfg.clickAction, "$key:click")
        if (cfg.mode != SurfaceMode.MOUSE) rt.wasTouched = touched
    }

    /** Bitmask of up(1) down(2) left(4) right(8). Eight-way overlaps adjacent directions on diagonals. */
    private fun directions(x: Float, y: Float, eightWay: Boolean): Int {
        val deg = (Math.toDegrees(atan2(y, x).toDouble()).toFloat() + 360f) % 360f
        fun near(center: Float, half: Float): Boolean {
            val d = abs(((deg - center + 540f) % 360f) - 180f)
            return d <= half
        }
        val half = if (eightWay) 67.5f else 45f
        var m = 0
        if (near(90f, half)) m = m or 1
        if (near(270f, half)) m = m or 2
        if (near(180f, half)) m = m or 4
        if (near(0f, half)) m = m or 8
        return m
    }

    private fun zoneHaptic(rt: SurfaceRt, zone: Int, enabled: Boolean, right: Boolean) {
        if (zone != rt.zone) {
            if (enabled && zone >= 0) hapticScaled(right, 900, 0, 1)
            rt.zone = zone
        }
    }

    private fun stickMouse(cfg: SurfaceConfig, x: Float, y: Float, mag: Float, dt: Float) {
        if (mag <= cfg.deadzone) return
        val t = min(1f, (mag - cfg.deadzone) / (1f - cfg.deadzone))
        val speed = STICK_MOUSE_PX_PER_S * cfg.sensitivity * t * t
        frame.mouseDx += x / mag * speed * dt
        frame.mouseDy += -y / mag * speed * dt * if (cfg.invertY) -1f else 1f
    }

    private fun padMouse(cfg: SurfaceConfig, rt: SurfaceRt, x: Float, y: Float, touched: Boolean, dt: Float, right: Boolean) {
        if (touched) {
            if (rt.wasTouched) {
                var dx = (x - rt.px) * PAD_MOUSE_PX * cfg.sensitivity
                var dy = -(y - rt.py) * PAD_MOUSE_PX * cfg.sensitivity * if (cfg.invertY) -1f else 1f
                // Light two-tap smoothing against sensor jitter.
                val sx = (dx + rt.lastDx) / 2f
                val sy = (dy + rt.lastDy) / 2f
                rt.lastDx = dx; rt.lastDy = dy
                dx = sx; dy = sy
                frame.mouseDx += dx
                frame.mouseDy += dy
                if (dt > 0f) {
                    rt.vx = rt.vx * 0.5f + dx / dt * 0.5f
                    rt.vy = rt.vy * 0.5f + dy / dt * 0.5f
                }
                if (cfg.haptics) {
                    rt.dist += hypot(dx, dy)
                    if (rt.dist >= MOUSE_TICK_PX) {
                        rt.dist = 0f
                        hapticScaled(right, 250, 0, 1)
                    }
                }
            } else {
                rt.vx = 0f; rt.vy = 0f; rt.lastDx = 0f; rt.lastDy = 0f
            }
            rt.px = x; rt.py = y
        } else if (cfg.trackball && hypot(rt.vx, rt.vy) > 30f) {
            frame.mouseDx += rt.vx * dt
            frame.mouseDy += rt.vy * dt
            val decay = exp(-cfg.friction.coerceIn(0.05f, 1f) * 5f * dt)
            rt.vx *= decay; rt.vy *= decay
        } else {
            rt.vx = 0f; rt.vy = 0f
        }
        rt.wasTouched = touched
    }

    private fun scrollWheel(
        cfg: SurfaceConfig, rt: SurfaceRt, x: Float, y: Float, mag: Float, touched: Boolean,
        key: String, nowMs: Long, haptics: Boolean, right: Boolean,
    ) {
        if (!touched || mag < 0.35f) {
            rt.hasAngle = false
            rt.angleAcc = 0f
            return
        }
        val ang = Math.toDegrees(atan2(y, x).toDouble()).toFloat()
        if (rt.hasAngle) {
            var d = ang - rt.angle
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            rt.angleAcc += d
            val step = cfg.scrollStepDeg.coerceAtLeast(5f)
            while (rt.angleAcc >= step) {
                rt.angleAcc -= step
                fireOnce(cfg.scrollCcw, "$key:ccw", nowMs)
                if (haptics) hapticScaled(right, 400, 0, 1)
            }
            while (rt.angleAcc <= -step) {
                rt.angleAcc += step
                fireOnce(cfg.scrollCw, "$key:cw", nowMs)
                if (haptics) hapticScaled(right, 400, 0, 1)
            }
        }
        rt.angle = ang
        rt.hasAngle = true
    }

    private fun addStick(t: StickTarget, x: Float, y: Float) {
        if (t == StickTarget.LEFT) { frame.lx += x; frame.ly += y } else { frame.rx += x; frame.ry += y }
    }

    private fun clampSticks() {
        fun clamp(x: Float, y: Float): Pair<Float, Float> {
            val m = hypot(x, y)
            return if (m > 1f) x / m to y / m else x to y
        }
        clamp(frame.lx, frame.ly).let { frame.lx = it.first; frame.ly = it.second }
        clamp(frame.rx, frame.ry).let { frame.rx = it.first; frame.ry = it.second }
    }

    // ---------------------------------------------------------------- gyro

    private fun updateGyroBias(s: RawState, nowMs: Long) {
        val gx = s.gyroX.toFloat()
        val gy = s.gyroY.toFloat()
        val gz = s.gyroZ.toFloat()
        if (calibrateUntil > 0L) {
            stillSum[0] += gx; stillSum[1] += gy; stillSum[2] += gz; stillCount++
            if (nowMs >= calibrateUntil) {
                if (stillCount > 0) for (i in 0..2) bias[i] = stillSum[i] / stillCount
                calibrateUntil = 0L
                calibrated = true
                stillCount = 0
                host.calibrated()
            }
            return
        }
        // Automatic: after 1.5 s of near-stillness, drift the bias towards the current reading.
        val still = abs(gx - bias[0]) < STILL_RAW && abs(gy - bias[1]) < STILL_RAW && abs(gz - bias[2]) < STILL_RAW
        if (!still) {
            stillSince = 0L
            return
        }
        if (stillSince == 0L) stillSince = nowMs
        if (nowMs - stillSince > 1500) {
            val alpha = if (calibrated) 0.002f else 0.02f
            bias[0] += (gx - bias[0]) * alpha
            bias[1] += (gy - bias[1]) * alpha
            bias[2] += (gz - bias[2]) * alpha
        }
    }

    private fun processGyro(s: RawState, dt: Float) {
        val g = profile.gyro
        val btnDown = s.buttons and g.button.bit != 0
        if (g.activation == GyroActivation.TOGGLE && btnDown && !gyroBtnPrev) gyroToggled = !gyroToggled
        gyroBtnPrev = btnDown
        if (g.mode == GyroMode.OFF || !gyroEnabled) return
        val active = when (g.activation) {
            GyroActivation.ALWAYS -> true
            GyroActivation.WHILE_HELD -> btnDown
            GyroActivation.WHILE_NOT_HELD -> !btnDown
            GyroActivation.TOGGLE -> gyroToggled
        }
        if (!active) return

        val pitch = (s.gyroX - bias[0]) / GYRO_LSB_PER_DPS
        val roll = (s.gyroY - bias[1]) / GYRO_LSB_PER_DPS
        val yaw = (s.gyroZ - bias[2]) / GYRO_LSB_PER_DPS
        // Screen-space rates (deg/s): right and down are positive.
        var h = if (g.horizontal == GyroAxis.YAW) -yaw else -roll
        var v = -pitch
        if (g.invertX) h = -h
        if (g.invertY) v = -v
        h = softThreshold(h, g.smallRotation)
        v = softThreshold(v, g.smallRotation)

        when (g.mode) {
            GyroMode.MOUSE -> {
                frame.mouseDx += h * dt * GYRO_PX_PER_DEG * g.sensX
                frame.mouseDy += v * dt * GYRO_PX_PER_DEG * g.sensY
            }
            GyroMode.STICK -> {
                var ox = h * g.sensX / GYRO_STICK_FULL_DPS
                var oy = -v * g.sensY / GYRO_STICK_FULL_DPS
                val m = hypot(ox, oy)
                if (m > 0.0005f) {
                    val boosted = min(1f, g.antiDeadzone + (1f - g.antiDeadzone) * m)
                    ox = ox / m * boosted
                    oy = oy / m * boosted
                    addStick(g.target, ox, oy)
                }
            }
            GyroMode.OFF -> {}
        }
    }

    /** Scales rotations below [threshold] down smoothly, removing hand tremor without a hard cutoff. */
    private fun softThreshold(v: Float, threshold: Float): Float {
        val a = abs(v)
        if (threshold <= 0f || a >= 2 * threshold) return v
        if (a <= threshold * 0.5f) return 0f
        return sign(v) * a * ((a - threshold * 0.5f) / (threshold * 1.5f))
    }

    // ---------------------------------------------------------------- output plumbing

    private fun apply(a: Action, key: String) {
        when (a.type) {
            ActionType.NONE -> {}
            ActionType.GAMEPAD -> {
                frame.gamepad = frame.gamepad or (1 shl a.code)
                if (a.code == GamepadOut.LT.ordinal) frame.lt = 1f
                if (a.code == GamepadOut.RT.ordinal) frame.rt = 1f
            }
            ActionType.KEY -> {
                if (Keys.isModifierKey(a.code)) frame.mods = frame.mods or Keys.modifierBit(a.code)
                else frame.keys += a.code
                frame.mods = frame.mods or a.mods
            }
            ActionType.MOUSE -> frame.mouseButtons = frame.mouseButtons or (1 shl a.code)
            ActionType.MEDIA -> frame.consumer += a.code
            ActionType.WHEEL, ActionType.SPECIAL -> edgesNow[key] = a
        }
    }

    /** One-shot action from a scroll step: one wheel notch, or a short tap of anything else. */
    private fun fireOnce(a: Action, key: String, nowMs: Long) {
        when (a.type) {
            ActionType.WHEEL -> wheel(a)
            ActionType.SPECIAL -> host.special(Special.entries[a.code])
            ActionType.NONE -> {}
            else -> pulses += Pulse(a, nowMs + TAP_MS, key)
        }
    }

    /** Wheel and special actions fire on press; wheel repeats while held. */
    private fun fireEdges(nowMs: Long) {
        for ((key, a) in edgesNow) {
            val next = edgeNext[key]
            if (next == null) {
                if (a.type == ActionType.WHEEL) wheel(a) else Special.entries.getOrNull(a.code)?.let { host.special(it) }
                edgeNext[key] = if (a.type == ActionType.WHEEL) nowMs + 350 else Long.MAX_VALUE
            } else if (a.type == ActionType.WHEEL && nowMs >= next) {
                wheel(a)
                edgeNext[key] = nowMs + 70
            }
        }
        edgeNext.keys.retainAll(edgesNow.keys)
    }

    private fun wheel(a: Action) {
        when (WheelOut.entries.getOrNull(a.code)) {
            WheelOut.UP -> frame.wheel += 1
            WheelOut.DOWN -> frame.wheel -= 1
            WheelOut.LEFT -> frame.pan -= 1
            WheelOut.RIGHT -> frame.pan += 1
            null -> {}
        }
    }

    // ---------------------------------------------------------------- haptics

    private fun hapticScaled(right: Boolean, onUs: Int, offUs: Int, count: Int) {
        val k = profile.hapticStrength / 100f
        if (k <= 0f) return
        host.haptic(right, (onUs * k).toInt().coerceAtLeast(1), offUs, count)
    }

    /** Confirmation buzz for shortcuts: [n] short pulses on both pads. */
    fun confirm(n: Int) {
        hapticScaled(true, 1500, 40_000, n)
        hapticScaled(false, 1500, 40_000, n)
    }

    private fun processRumble(nowMs: Long) {
        val k = profile.hapticStrength / 100f
        val on = profile.rumble && nowMs <= rumbleUntil && k > 0f && (rumbleStrong > 0.01f || rumbleWeak > 0.01f)
        if (!on) {
            if (rumbleActive) {
                rumbleActive = false
                host.rumble(0f, 0f)
            }
            return
        }
        if (nowMs < nextRumble) return
        nextRumble = nowMs + RUMBLE_INTERVAL_MS
        rumbleActive = true
        host.rumble(rumbleStrong * k, rumbleWeak * k)
    }

    // ---------------------------------------------------------------- on-screen menu

    private fun handleMenu(s: RawState, buttons: Int) {
        frame.clearLevels()
        val pressedNow = buttons and prevButtons.inv()
        if (pressedNow and (Btn.A.bit or Btn.RPAD_CLICK.bit or Btn.RT_CLICK.bit) != 0) host.menuNavigate(MenuNav.SELECT)
        if (pressedNow and (Btn.B.bit or Btn.BACK.bit or Btn.QAM.bit) != 0) host.menuNavigate(MenuNav.CLOSE)
        // Up/down from the stick, the left pad or the d-pad.
        var y = s.stickY / 32767f
        if (s.pressed(Btn.LPAD_TOUCH) && abs(s.lpadY / 32767f) > abs(y)) y = s.lpadY / 32767f
        if (s.pressed(Btn.DPAD_UP)) y = 1f
        if (s.pressed(Btn.DPAD_DOWN)) y = -1f
        val dir = when {
            y > 0.5f -> 1
            y < -0.5f -> -1
            else -> 0
        }
        if (dir != menuPrevDir && dir != 0) host.menuNavigate(if (dir > 0) MenuNav.UP else MenuNav.DOWN)
        menuPrevDir = dir
    }

    companion object {
        const val HOLD_MS = 400L
        const val TAP_MS = 60L
        const val TURBO_HALF_PERIOD_MS = 50L
        const val POWER_OFF_MS = 3000L
        const val PAD_MOUSE_PX = 700f
        const val STICK_MOUSE_PX_PER_S = 1600f
        const val MOUSE_TICK_PX = 40f
        const val GYRO_LSB_PER_DPS = 16.4f
        const val GYRO_PX_PER_DEG = 12f
        const val GYRO_STICK_FULL_DPS = 220f
        const val STILL_RAW = 50f
        const val RUMBLE_INTERVAL_MS = 50L
    }
}
