package dev.scmapper.mapping

import org.json.JSONArray
import org.json.JSONObject

/**
 * Controller-independent inputs. Every controller driver translates its own report into these
 * bits ([bit]); inputs a controller doesn't have simply never fire. [bindable] inputs get a
 * [ButtonBinding]; the rest are handled by the pad/stick/trigger configs or used as gyro switches.
 * Names are stored in profiles, so never rename an entry.
 */
enum class Btn(val label: String, val bindable: Boolean = true) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    LB("Left bumper"),
    RB("Right bumper"),
    BACK("Back / View (◀)"),
    START("Start / Menu (▶)"),
    STEAM("Steam button (tap)"),
    QAM("Quick access (…) — 2026"),
    LGRIP("Left back button 1 (2015 left grip)"),
    RGRIP("Right back button 1 (2015 right grip)"),
    LGRIP2("Left back button 2 — 2026"),
    RGRIP2("Right back button 2 — 2026"),
    STICK_CLICK("Left stick click"),
    RSTICK_CLICK("Right stick click — 2026"),
    DPAD_UP("D-pad up — 2026"),
    DPAD_DOWN("D-pad down — 2026"),
    DPAD_LEFT("D-pad left — 2026"),
    DPAD_RIGHT("D-pad right — 2026"),
    LT_CLICK("Left trigger full pull", bindable = false),
    RT_CLICK("Right trigger full pull", bindable = false),
    LPAD_CLICK("Left pad click", bindable = false),
    RPAD_CLICK("Right pad click", bindable = false),
    LPAD_TOUCH("Left pad touch", bindable = false),
    RPAD_TOUCH("Right pad touch", bindable = false),
    LSTICK_TOUCH("Left stick touch — 2026", bindable = false),
    RSTICK_TOUCH("Right stick touch — 2026", bindable = false),
    LGRIP_TOUCH("Left grip touch — 2026", bindable = false),
    RGRIP_TOUCH("Right grip touch — 2026", bindable = false),
    ;

    val bit: Int get() = 1 shl ordinal
}

enum class ActionType { NONE, GAMEPAD, KEY, MOUSE, WHEEL, MEDIA, SPECIAL }

enum class GamepadOut(val label: String) {
    A("A"), B("B"), X("X"), Y("Y"), LB("LB"), RB("RB"), LT("LT (full)"), RT("RT (full)"),
    BACK("Back / View"), START("Start / Menu"), GUIDE("Guide / Home"), L3("Left stick click"), R3("Right stick click"),
    DPAD_UP("D-pad up"), DPAD_DOWN("D-pad down"), DPAD_LEFT("D-pad left"), DPAD_RIGHT("D-pad right"),
}

enum class MouseOut(val label: String) { LEFT("Left click"), RIGHT("Right click"), MIDDLE("Middle click"), BACK("Mouse back"), FORWARD("Mouse forward") }

enum class WheelOut(val label: String) { UP("Scroll up"), DOWN("Scroll down"), LEFT("Scroll left"), RIGHT("Scroll right") }

enum class Special(val label: String) {
    NEXT_PROFILE("Next profile"),
    PREV_PROFILE("Previous profile"),
    PROFILE_MENU("Open profile menu"),
    TOGGLE_GYRO("Toggle gyro on/off"),
}

/** One output: a gamepad button, a key (with modifiers), a mouse button, ... */
data class Action(val type: ActionType = ActionType.NONE, val code: Int = 0, val mods: Int = 0) {
    val isNone get() = type == ActionType.NONE

    fun label(): String = when (type) {
        ActionType.NONE -> "—"
        ActionType.GAMEPAD -> "Gamepad " + GamepadOut.entries.getOrNull(code)?.label
        ActionType.KEY -> "Key " + Keys.modsName(mods) + Keys.keyName(code)
        ActionType.MOUSE -> MouseOut.entries.getOrNull(code)?.label ?: "Mouse"
        ActionType.WHEEL -> WheelOut.entries.getOrNull(code)?.label ?: "Wheel"
        ActionType.MEDIA -> Keys.mediaName(code)
        ActionType.SPECIAL -> Special.entries.getOrNull(code)?.label ?: "Special"
    }

    fun toJson(): JSONObject = JSONObject().put("t", type.name).put("c", code).put("m", mods)

    companion object {
        val NONE = Action()
        fun gp(o: GamepadOut) = Action(ActionType.GAMEPAD, o.ordinal)
        fun key(usage: Int, mods: Int = 0) = Action(ActionType.KEY, usage, mods)
        fun mouse(o: MouseOut) = Action(ActionType.MOUSE, o.ordinal)
        fun wheel(o: WheelOut) = Action(ActionType.WHEEL, o.ordinal)
        fun media(usage: Int) = Action(ActionType.MEDIA, usage)
        fun special(s: Special) = Action(ActionType.SPECIAL, s.ordinal)

        fun fromJson(o: JSONObject?): Action {
            if (o == null) return NONE
            return Action(enumOr(o.optString("t"), ActionType.NONE), o.optInt("c"), o.optInt("m"))
        }
    }
}

class ButtonBinding(
    var press: Action = Action.NONE,
    /** If set, a long press fires this instead and a short press only taps [press]. */
    var hold: Action = Action.NONE,
    /** Repeatedly presses [press] while held. */
    var turbo: Boolean = false,
) {
    fun copy() = ButtonBinding(press, hold, turbo)
    fun toJson(): JSONObject = JSONObject().put("press", press.toJson()).put("hold", hold.toJson()).put("turbo", turbo)

    companion object {
        fun fromJson(o: JSONObject?) = ButtonBinding(
            Action.fromJson(o?.optJSONObject("press")),
            Action.fromJson(o?.optJSONObject("hold")),
            o?.optBoolean("turbo") ?: false,
        )
    }
}

enum class SurfaceMode(val label: String) {
    NONE("Disabled"),
    JOYSTICK("Analog stick"),
    DIRECTIONAL("Directional buttons (D-pad / ABXY)"),
    GRID("Touch zones (grid of buttons)"),
    MOUSE("Mouse / trackball"),
    SCROLL("Scroll wheel (circular)"),
}

enum class StickTarget(val label: String) { LEFT("Left stick"), RIGHT("Right stick") }

/**
 * Configuration for one 2D input: the left pad, the right pad or the analog stick.
 * Only the fields relevant to [mode] are used.
 */
class SurfaceConfig(
    var mode: SurfaceMode = SurfaceMode.NONE,
    var target: StickTarget = StickTarget.RIGHT,
    var deadzone: Float = 0.1f,
    var sensitivity: Float = 1.0f,
    var invertY: Boolean = false,
    /** Zone/stick modes only react while the pad is clicked down. */
    var requireClick: Boolean = false,
    var eightWay: Boolean = true,
    /** Up, down, left, right. */
    var dirActions: MutableList<Action> = MutableList(4) { Action.NONE },
    var centerAction: Action = Action.NONE,
    var rows: Int = 2,
    var cols: Int = 2,
    var gridActions: MutableList<Action> = MutableList(16) { Action.NONE },
    var trackball: Boolean = true,
    /** Trackball friction: fraction of speed lost per second. */
    var friction: Float = 0.9f,
    var scrollCw: Action = Action.wheel(WheelOut.DOWN),
    var scrollCcw: Action = Action.wheel(WheelOut.UP),
    var scrollStepDeg: Float = 30f,
    var clickAction: Action = Action.NONE,
    var rotationDeg: Float = 0f,
    var haptics: Boolean = true,
) {
    fun copy(): SurfaceConfig = fromJson(toJson())

    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode.name).put("target", target.name).put("deadzone", deadzone.toDouble())
        .put("sensitivity", sensitivity.toDouble()).put("invertY", invertY).put("requireClick", requireClick)
        .put("eightWay", eightWay).put("dir", actionsJson(dirActions)).put("center", centerAction.toJson())
        .put("rows", rows).put("cols", cols).put("grid", actionsJson(gridActions))
        .put("trackball", trackball).put("friction", friction.toDouble())
        .put("scrollCw", scrollCw.toJson()).put("scrollCcw", scrollCcw.toJson()).put("scrollStep", scrollStepDeg.toDouble())
        .put("click", clickAction.toJson()).put("rotation", rotationDeg.toDouble()).put("haptics", haptics)

    companion object {
        fun fromJson(o: JSONObject?): SurfaceConfig {
            val d = SurfaceConfig()
            if (o == null) return d
            return SurfaceConfig(
                mode = enumOr(o.optString("mode"), d.mode),
                target = enumOr(o.optString("target"), d.target),
                deadzone = o.optDouble("deadzone", d.deadzone.toDouble()).toFloat(),
                sensitivity = o.optDouble("sensitivity", d.sensitivity.toDouble()).toFloat(),
                invertY = o.optBoolean("invertY", d.invertY),
                requireClick = o.optBoolean("requireClick", d.requireClick),
                eightWay = o.optBoolean("eightWay", d.eightWay),
                dirActions = actionsFromJson(o.optJSONArray("dir"), 4),
                centerAction = Action.fromJson(o.optJSONObject("center")),
                rows = o.optInt("rows", d.rows).coerceIn(1, 4),
                cols = o.optInt("cols", d.cols).coerceIn(1, 4),
                gridActions = actionsFromJson(o.optJSONArray("grid"), 16),
                trackball = o.optBoolean("trackball", d.trackball),
                friction = o.optDouble("friction", d.friction.toDouble()).toFloat(),
                scrollCw = o.optJSONObject("scrollCw")?.let { Action.fromJson(it) } ?: d.scrollCw,
                scrollCcw = o.optJSONObject("scrollCcw")?.let { Action.fromJson(it) } ?: d.scrollCcw,
                scrollStepDeg = o.optDouble("scrollStep", d.scrollStepDeg.toDouble()).toFloat(),
                clickAction = Action.fromJson(o.optJSONObject("click")),
                rotationDeg = o.optDouble("rotation", 0.0).toFloat(),
                haptics = o.optBoolean("haptics", d.haptics),
            )
        }
    }
}

enum class TriggerTarget(val label: String) { NONE("No analog output"), LEFT("Left trigger (LT)"), RIGHT("Right trigger (RT)") }

class TriggerConfig(
    var analog: TriggerTarget = TriggerTarget.NONE,
    /** Fires when pulled past [softThreshold]. */
    var softAction: Action = Action.NONE,
    var softThreshold: Float = 0.5f,
    /** Fires on the physical full-pull click at the end of travel. */
    var fullAction: Action = Action.NONE,
    var deadzone: Float = 0.02f,
) {
    fun copy(): TriggerConfig = fromJson(toJson())
    fun toJson(): JSONObject = JSONObject().put("analog", analog.name).put("soft", softAction.toJson())
        .put("softThreshold", softThreshold.toDouble()).put("full", fullAction.toJson()).put("deadzone", deadzone.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): TriggerConfig {
            if (o == null) return TriggerConfig()
            return TriggerConfig(
                enumOr(o.optString("analog"), TriggerTarget.NONE),
                Action.fromJson(o.optJSONObject("soft")),
                o.optDouble("softThreshold", 0.5).toFloat(),
                Action.fromJson(o.optJSONObject("full")),
                o.optDouble("deadzone", 0.02).toFloat(),
            )
        }
    }
}

enum class GyroMode(val label: String) { OFF("Off"), MOUSE("Mouse"), STICK("Joystick (camera)") }

enum class GyroActivation(val label: String) {
    ALWAYS("Always on"),
    WHILE_HELD("On while button held"),
    WHILE_NOT_HELD("Off while button held (ratchet)"),
    TOGGLE("Toggle with button"),
}

enum class GyroAxis(val label: String) { YAW("Yaw (turn left/right)"), ROLL("Roll (tilt left/right)") }

class GyroConfig(
    var mode: GyroMode = GyroMode.OFF,
    var target: StickTarget = StickTarget.RIGHT,
    var activation: GyroActivation = GyroActivation.ALWAYS,
    var button: Btn = Btn.RPAD_TOUCH,
    var sensX: Float = 1.0f,
    var sensY: Float = 1.0f,
    var invertX: Boolean = false,
    var invertY: Boolean = false,
    var horizontal: GyroAxis = GyroAxis.YAW,
    /** Joystick mode: minimum deflection once moving, to get past the game's own deadzone. */
    var antiDeadzone: Float = 0.15f,
    /** Rotation speed (deg/s) treated as noise and ignored. */
    var smallRotation: Float = 0.6f,
) {
    fun copy(): GyroConfig = fromJson(toJson())
    fun toJson(): JSONObject = JSONObject().put("mode", mode.name).put("target", target.name)
        .put("activation", activation.name).put("button", button.name)
        .put("sensX", sensX.toDouble()).put("sensY", sensY.toDouble()).put("invertX", invertX).put("invertY", invertY)
        .put("horizontal", horizontal.name).put("antiDeadzone", antiDeadzone.toDouble()).put("smallRotation", smallRotation.toDouble())

    companion object {
        fun fromJson(o: JSONObject?): GyroConfig {
            val d = GyroConfig()
            if (o == null) return d
            return GyroConfig(
                enumOr(o.optString("mode"), d.mode), enumOr(o.optString("target"), d.target),
                enumOr(o.optString("activation"), d.activation), enumOr(o.optString("button"), d.button),
                o.optDouble("sensX", 1.0).toFloat(), o.optDouble("sensY", 1.0).toFloat(),
                o.optBoolean("invertX"), o.optBoolean("invertY"), enumOr(o.optString("horizontal"), d.horizontal),
                o.optDouble("antiDeadzone", d.antiDeadzone.toDouble()).toFloat(),
                o.optDouble("smallRotation", d.smallRotation.toDouble()).toFloat(),
            )
        }
    }
}

class Profile(
    var name: String,
    val buttons: MutableMap<Btn, ButtonBinding> = Btn.entries.filter { it.bindable }.associateWith { ButtonBinding() }.toMutableMap(),
    var leftTrigger: TriggerConfig = TriggerConfig(),
    var rightTrigger: TriggerConfig = TriggerConfig(),
    var stick: SurfaceConfig = SurfaceConfig(),
    /** The 2026 controller's right stick. */
    var rightStick: SurfaceConfig = SurfaceConfig(mode = SurfaceMode.JOYSTICK, target = StickTarget.RIGHT, deadzone = 0.08f),
    var leftPad: SurfaceConfig = SurfaceConfig(),
    var rightPad: SurfaceConfig = SurfaceConfig(),
    var gyro: GyroConfig = GyroConfig(),
    /** 0..100, scales every haptic effect. */
    var hapticStrength: Int = 60,
    /** Forward game rumble to the trackpad actuators. */
    var rumble: Boolean = true,
    var ledBrightness: Int = 50,
) {
    fun binding(b: Btn): ButtonBinding = buttons.getOrPut(b) { ButtonBinding() }

    fun copy(newName: String = name): Profile = fromJson(toJson()).also { it.name = newName }

    /** Every action this profile can produce, used to decide which virtual devices it needs. */
    fun allActions(): List<Action> = buildList {
        buttons.values.forEach { add(it.press); add(it.hold) }
        for (t in listOf(leftTrigger, rightTrigger)) { add(t.softAction); add(t.fullAction) }
        for (s in surfaces()) {
            addAll(s.dirActions); add(s.centerAction); addAll(s.gridActions.take(s.rows * s.cols))
            add(s.clickAction)
            if (s.mode == SurfaceMode.SCROLL) { add(s.scrollCw); add(s.scrollCcw) }
        }
    }

    fun usesMouse() = gyro.mode == GyroMode.MOUSE ||
        surfaces().any { it.mode == SurfaceMode.MOUSE } ||
        allActions().any { it.type == ActionType.MOUSE || it.type == ActionType.WHEEL }

    fun surfaces() = listOf(stick, rightStick, leftPad, rightPad)

    fun usesKeyboard() = allActions().any { it.type == ActionType.KEY || it.type == ActionType.MEDIA }

    fun toJson(): JSONObject {
        val b = JSONObject()
        buttons.forEach { (k, v) -> b.put(k.name, v.toJson()) }
        return JSONObject().put("version", 1).put("name", name).put("buttons", b)
            .put("leftTrigger", leftTrigger.toJson()).put("rightTrigger", rightTrigger.toJson())
            .put("stick", stick.toJson()).put("rightStick", rightStick.toJson()).put("leftPad", leftPad.toJson()).put("rightPad", rightPad.toJson())
            .put("gyro", gyro.toJson()).put("haptics", hapticStrength).put("rumble", rumble).put("led", ledBrightness)
    }

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val p = Profile(o.optString("name", "Profile"))
            val b = o.optJSONObject("buttons")
            if (b != null) for (key in b.keys()) {
                val btn = enumOrNull<Btn>(key) ?: continue
                if (btn.bindable) p.buttons[btn] = ButtonBinding.fromJson(b.optJSONObject(key))
            }
            p.leftTrigger = TriggerConfig.fromJson(o.optJSONObject("leftTrigger"))
            p.rightTrigger = TriggerConfig.fromJson(o.optJSONObject("rightTrigger"))
            p.stick = SurfaceConfig.fromJson(o.optJSONObject("stick"))
            o.optJSONObject("rightStick")?.let { p.rightStick = SurfaceConfig.fromJson(it) }
            p.leftPad = SurfaceConfig.fromJson(o.optJSONObject("leftPad"))
            p.rightPad = SurfaceConfig.fromJson(o.optJSONObject("rightPad"))
            p.gyro = GyroConfig.fromJson(o.optJSONObject("gyro"))
            p.hapticStrength = o.optInt("haptics", 60).coerceIn(0, 100)
            p.rumble = o.optBoolean("rumble", true)
            p.ledBrightness = o.optInt("led", 50).coerceIn(0, 100)
            return p
        }
    }
}

private fun actionsJson(list: List<Action>) = JSONArray().apply { list.forEach { put(it.toJson()) } }

private fun actionsFromJson(a: JSONArray?, size: Int): MutableList<Action> =
    MutableList(size) { i -> if (a != null && i < a.length()) Action.fromJson(a.optJSONObject(i)) else Action.NONE }

inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
    if (name == null) null else enumValues<E>().firstOrNull { it.name == name }

inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E = enumOrNull<E>(name) ?: default
