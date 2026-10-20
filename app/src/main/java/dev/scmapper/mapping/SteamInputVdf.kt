package dev.scmapper.mapping

import dev.scmapper.mapping.GamepadOut as G

/**
 * Converts between Steam Input controller configurations (.vdf, as shared on steaminputdb.com or
 * exported by Steam) and SC Mapper profiles.
 *
 * Steam Input is far richer (action sets, layers, mode shifts, chords, double press, ...), so the
 * import takes the default action set's active groups and the press / long-press activators, and
 * reports what it skipped.
 */
object SteamInputVdf {
    class ImportResult(val profile: Profile, val notes: List<String>)

    // ------------------------------------------------------------------ import

    fun import(text: String, fallbackName: String): ImportResult {
        val root = Vdf.parse(text)
        val cm = root.child("controller_mappings") ?: root
        val notes = LinkedHashSet<String>()
        val title = cm.str("title")?.takeIf { it.isNotBlank() && !it.startsWith("#") }
            ?: cm.child("localization")?.child("english")?.str("title")
            ?: fallbackName
        val p = Profile(title.ifBlank { fallbackName })
        // Start from a clean slate: nothing bound, sticks/pads disabled unless the config says otherwise.
        p.rightStick = SurfaceConfig()

        val groups = cm.children("group").associateBy { it.str("id") ?: "" }
        val presets = cm.children("preset")
        if (presets.size > 1) notes += "Only the first action set was imported (${presets.size} found)."
        val preset = presets.firstOrNull { it.str("id") == "0" } ?: presets.firstOrNull()
        if (preset == null) {
            notes += "No preset found in this file."
            return ImportResult(p, notes.toList())
        }
        val gsb = preset.child("group_source_bindings")
        for ((groupId, value) in gsb?.entries.orEmpty()) {
            if (value !is String) continue
            val parts = value.trim().split(Regex("\\s+"))
            val source = parts.getOrNull(0) ?: continue
            if (parts.getOrNull(1) != "active") continue
            if (parts.size > 2) {
                notes += "Mode shifts and layers aren't supported and were skipped."
                continue
            }
            val group = groups[groupId] ?: continue
            applyGroup(p, source, group, notes)
        }
        if (cm.children("action_layers").any { it.entries.isNotEmpty() }) notes += "Action layers were ignored."
        return ImportResult(p, notes.toList())
    }

    private fun applyGroup(p: Profile, source: String, group: VdfNode, notes: MutableSet<String>) {
        val mode = group.str("mode") ?: return
        val inputs = group.child("inputs") ?: VdfNode()
        val settings = group.child("settings") ?: VdfNode()
        fun press(input: String) = activator(inputs.child(input), "Full_Press", notes)
        fun hold(input: String) = activator(inputs.child(input), "Long_Press", notes)
        fun bindButton(btn: Btn, input: String) {
            if (inputs.child(input) == null) return
            p.binding(btn).press = press(input)
            p.binding(btn).hold = hold(input)
        }
        when (source) {
            "button_diamond" -> when (mode) {
                "four_buttons" -> {
                    bindButton(Btn.A, "button_a"); bindButton(Btn.B, "button_b")
                    bindButton(Btn.X, "button_x"); bindButton(Btn.Y, "button_y")
                }
                else -> notes += "Face buttons in \"$mode\" mode aren't supported."
            }
            "switch" -> {
                for ((input, btn) in SWITCHES) bindButton(btn, input)
            }
            "dpad" -> when (mode) {
                "dpad" -> {
                    bindButton(Btn.DPAD_UP, "dpad_north"); bindButton(Btn.DPAD_DOWN, "dpad_south")
                    bindButton(Btn.DPAD_LEFT, "dpad_west"); bindButton(Btn.DPAD_RIGHT, "dpad_east")
                }
                "four_buttons" -> {
                    bindButton(Btn.DPAD_UP, "button_y"); bindButton(Btn.DPAD_DOWN, "button_a")
                    bindButton(Btn.DPAD_LEFT, "button_x"); bindButton(Btn.DPAD_RIGHT, "button_b")
                }
                else -> notes += "D-pad in \"$mode\" mode isn't supported."
            }
            "left_trigger", "right_trigger" -> {
                val left = source == "left_trigger"
                val t = TriggerConfig()
                t.analog = when (settings.str("output_trigger")) {
                    "0" -> TriggerTarget.NONE
                    "1" -> TriggerTarget.LEFT
                    "2" -> TriggerTarget.RIGHT
                    else -> if (mode == "trigger") (if (left) TriggerTarget.LEFT else TriggerTarget.RIGHT) else TriggerTarget.NONE
                }
                t.fullAction = press("click")
                t.softAction = press("edge")
                if (left) p.leftTrigger = t else p.rightTrigger = t
            }
            "joystick", "left_joystick" -> p.stick = surface(mode, inputs, settings, isStick = true, right = false, notes) { p.binding(Btn.STICK_CLICK).press = it }
            "right_joystick" -> p.rightStick = surface(mode, inputs, settings, isStick = true, right = true, notes) { p.binding(Btn.RSTICK_CLICK).press = it }
            "left_trackpad" -> p.leftPad = surface(mode, inputs, settings, isStick = false, right = false, notes, null)
            "right_trackpad" -> p.rightPad = surface(mode, inputs, settings, isStick = false, right = true, notes, null)
            "gyro" -> {
                val g = GyroConfig()
                when (mode) {
                    "absolute_mouse", "mouse_joystick", "mouse_region", "relative_mouse" -> g.mode = GyroMode.MOUSE
                    "joystick_camera", "joystick_move", "joystick_mouse" -> {
                        g.mode = GyroMode.STICK
                        g.target = if (mode == "joystick_move" && settings.str("output_joystick") == "0") StickTarget.LEFT else StickTarget.RIGHT
                    }
                    else -> notes += "Gyro in \"$mode\" mode isn't supported."
                }
                if (g.mode != GyroMode.OFF && settings.str("gyro_button") != null && settings.str("gyro_button") != "0") {
                    g.activation = GyroActivation.WHILE_HELD
                    g.button = Btn.RPAD_TOUCH
                    notes += "Gyro activation button was approximated as \"right pad touch\"; check the gyro settings."
                }
                p.gyro = g
            }
            else -> notes += "Input \"$source\" isn't available on Steam Controllers and was skipped."
        }
    }

    private fun surface(
        mode: String, inputs: VdfNode, settings: VdfNode, isStick: Boolean, right: Boolean,
        notes: MutableSet<String>, onStickClick: ((Action) -> Unit)?,
    ): SurfaceConfig {
        val s = SurfaceConfig()
        fun press(input: String) = activator(inputs.child(input), "Full_Press", notes)
        val click = press("click")
        if (isStick) onStickClick?.invoke(click) else s.clickAction = click
        val requiresClick = settings.str("requires_click")
        when (mode) {
            "dpad" -> {
                s.mode = SurfaceMode.DIRECTIONAL
                s.dirActions = mutableListOf(press("dpad_north"), press("dpad_south"), press("dpad_west"), press("dpad_east"))
                s.deadzone = if (isStick) 0.4f else 0.25f
                s.requireClick = !isStick && requiresClick != "0"
                if (s.requireClick) s.clickAction = Action.NONE
            }
            "four_buttons" -> {
                s.mode = SurfaceMode.DIRECTIONAL
                s.eightWay = false
                s.dirActions = mutableListOf(press("button_y"), press("button_a"), press("button_x"), press("button_b"))
                s.deadzone = if (isStick) 0.4f else 0.15f
                s.requireClick = !isStick && requiresClick != "0"
                if (s.requireClick) s.clickAction = Action.NONE
            }
            "joystick_move", "joystick_camera", "joystick_mouse" -> {
                s.mode = SurfaceMode.JOYSTICK
                s.target = when (settings.str("output_joystick")) {
                    "0" -> StickTarget.LEFT
                    "1" -> StickTarget.RIGHT
                    else -> if (mode == "joystick_camera" || right) StickTarget.RIGHT else StickTarget.LEFT
                }
                s.deadzone = if (isStick) 0.08f else 0.05f
            }
            "absolute_mouse", "mouse_joystick", "mouse_region", "relative_mouse" -> {
                s.mode = SurfaceMode.MOUSE
                s.trackball = settings.str("trackball") != "0"
                s.deadzone = 0.1f
            }
            "scrollwheel" -> {
                s.mode = SurfaceMode.SCROLL
                s.scrollCw = press("scroll_clockwise").takeUnless { it.isNone } ?: Action.wheel(WheelOut.DOWN)
                s.scrollCcw = press("scroll_counterclockwise").takeUnless { it.isNone } ?: Action.wheel(WheelOut.UP)
            }
            "touch_menu", "radial_menu" -> {
                val buttons = (0 until 20).map { press("touch_menu_button_$it") }
                val count = settings.str("touch_menu_button_count")?.toIntOrNull()
                    ?: (buttons.indexOfLast { !it.isNone } + 1).coerceAtLeast(1)
                val (rows, cols) = gridFor(count)
                s.mode = SurfaceMode.GRID
                s.rows = rows; s.cols = cols
                for (i in 0 until minOf(count, 16)) s.gridActions[i] = buttons[i]
                s.requireClick = true
                if (mode == "radial_menu") notes += "Radial menus were converted to a grid of touch zones."
            }
            "single_button" -> s.mode = SurfaceMode.NONE
            else -> notes += "\"$mode\" mode isn't supported and was left disabled."
        }
        return s
    }

    private fun gridFor(count: Int): Pair<Int, Int> = when {
        count <= 2 -> 1 to 2
        count <= 4 -> 2 to 2
        count <= 6 -> 2 to 3
        count <= 9 -> 3 to 3
        count <= 12 -> 3 to 4
        else -> 4 to 4
    }

    /** Reads one activator's bindings (e.g. "Full_Press") into an action. */
    private fun activator(input: VdfNode?, type: String, notes: MutableSet<String>): Action {
        val activators = input?.child("activators") ?: return Action.NONE
        for ((name, v) in activators.entries) {
            if (v !is VdfNode) continue
            if (!name.equals(type, true)) {
                if (name !in listOf("Full_Press", "Long_Press")) notes += "\"${name.replace('_', ' ')}\" activators aren't supported and were skipped."
                continue
            }
            return bindingsToAction(v.child("bindings")?.strings("binding").orEmpty(), notes)
        }
        return Action.NONE
    }

    /** Several Steam bindings (e.g. LEFT_CONTROL + P) are merged into one key with modifiers. */
    fun bindingsToAction(bindings: List<String>, notes: MutableSet<String> = HashSet()): Action {
        var key = -1
        var mods = 0
        var other: Action? = null
        for (b in bindings) {
            val a = parseBinding(b, notes) ?: continue
            when {
                a.type == ActionType.KEY && Keys.isModifierKey(a.code) -> mods = mods or Keys.modifierBit(a.code)
                a.type == ActionType.KEY -> if (key < 0) key = a.code
                other == null && !a.isNone -> other = a
            }
        }
        if (key >= 0) return Action.key(key, mods and 0x0F or (mods shr 4))
        if (mods != 0) {
            // Only modifiers: the first one is the key, the rest are held with it.
            val first = (0 until 8).first { mods and (1 shl it) != 0 }
            val rest = mods and (1 shl first).inv()
            return Action.key(224 + first, rest and 0x0F or (rest shr 4))
        }
        return other ?: Action.NONE
    }

    private fun parseBinding(binding: String, notes: MutableSet<String>): Action? {
        val head = binding.substringBefore(',').trim()
        val verb = head.substringBefore(' ').lowercase()
        val arg = head.substringAfter(' ', "").trim()
        val argKey = arg.uppercase().replace(" ", "_")
        return when (verb) {
            "xinput_button" -> XINPUT[argKey]?.let { Action.gp(it) } ?: run { notes += "Unknown gamepad button \"$arg\"."; null }
            "key_press" -> keyUsage(arg)?.let { Action.key(it) } ?: run { notes += "Unknown key \"$arg\"."; null }
            "mouse_button" -> MOUSE[argKey]?.let { Action.mouse(it) } ?: run { notes += "Unknown mouse button \"$arg\"."; null }
            "mouse_wheel" -> when {
                "UP" in argKey -> Action.wheel(WheelOut.UP)
                "DOWN" in argKey -> Action.wheel(WheelOut.DOWN)
                "LEFT" in argKey -> Action.wheel(WheelOut.LEFT)
                "RIGHT" in argKey -> Action.wheel(WheelOut.RIGHT)
                else -> null
            }
            "controller_action" -> {
                if (argKey.startsWith("CHANGE_PRESET")) notes += "Action set switching was skipped."
                else if (argKey != "EMPTY_BINDING") notes += "Steam action \"${arg.lowercase()}\" isn't available and was skipped."
                null
            }
            "mode_shift" -> { notes += "Mode shifts aren't supported and were skipped."; null }
            else -> { if (verb.isNotEmpty()) notes += "Binding type \"$verb\" isn't supported."; null }
        }
    }

    private val SWITCHES = listOf(
        "button_escape" to Btn.START,
        "button_menu" to Btn.BACK,
        "left_bumper" to Btn.LB,
        "right_bumper" to Btn.RB,
        "button_back_left" to Btn.LGRIP,
        "button_back_right" to Btn.RGRIP,
        "button_back_left_upper" to Btn.LGRIP2,
        "button_back_right_upper" to Btn.RGRIP2,
        "button_capture" to Btn.QAM,
    )

    private val XINPUT = mapOf(
        "A" to G.A, "B" to G.B, "X" to G.X, "Y" to G.Y,
        "SHOULDER_LEFT" to G.LB, "SHOULDER_RIGHT" to G.RB,
        "TRIGGER_LEFT" to G.LT, "TRIGGER_RIGHT" to G.RT,
        "SELECT" to G.BACK, "BACK" to G.BACK, "START" to G.START, "GUIDE" to G.GUIDE, "HOME" to G.GUIDE,
        "JOYSTICK_LEFT" to G.L3, "JOYSTICK_RIGHT" to G.R3,
        "DPAD_UP" to G.DPAD_UP, "DPAD_DOWN" to G.DPAD_DOWN, "DPAD_LEFT" to G.DPAD_LEFT, "DPAD_RIGHT" to G.DPAD_RIGHT,
    )

    private val MOUSE = mapOf(
        "LEFT" to MouseOut.LEFT, "RIGHT" to MouseOut.RIGHT, "MIDDLE" to MouseOut.MIDDLE,
        "BACK" to MouseOut.BACK, "X1" to MouseOut.BACK, "FORWARD" to MouseOut.FORWARD, "X2" to MouseOut.FORWARD,
    )

    /** Steam key names, canonical form first (used for export). */
    private val KEY_NAMES: List<Pair<String, Int>> = buildList {
        for (i in 0 until 26) add(('A' + i).toString() to 4 + i)
        for (i in 1..9) add(i.toString() to 29 + i)
        add("0" to 39)
        addAll(
            listOf(
                "RETURN" to 40, "ENTER" to 40, "ESCAPE" to 41, "BACKSPACE" to 42, "TAB" to 43, "SPACE" to 44,
                "DASH" to 45, "MINUS" to 45, "EQUALS" to 46, "LEFT_BRACKET" to 47, "RIGHT_BRACKET" to 48,
                "BACKSLASH" to 49, "SEMICOLON" to 51, "SINGLE_QUOTE" to 52, "APOSTROPHE" to 52,
                "BACK_TICK" to 53, "GRAVE" to 53, "TILDE" to 53, "COMMA" to 54, "PERIOD" to 55,
                "FORWARD_SLASH" to 56, "SLASH" to 56, "CAPSLOCK" to 57, "CAPS_LOCK" to 57,
            )
        )
        for (i in 1..12) add("F$i" to 57 + i)
        addAll(
            listOf(
                "PRINTSCREEN" to 70, "PRINT_SCREEN" to 70, "SCROLLLOCK" to 71, "SCROLL_LOCK" to 71, "PAUSE" to 72,
                "INSERT" to 73, "HOME" to 74, "PAGE_UP" to 75, "DELETE" to 76, "END" to 77, "PAGE_DOWN" to 78,
                "RIGHT_ARROW" to 79, "LEFT_ARROW" to 80, "DOWN_ARROW" to 81, "UP_ARROW" to 82,
                "RIGHT" to 79, "LEFT" to 80, "DOWN" to 81, "UP" to 82,
                "APPS" to 101, "MENU" to 101,
                "LEFT_CONTROL" to 224, "LEFT_CTRL" to 224, "LEFT_SHIFT" to 225, "LEFT_ALT" to 226,
                "LEFT_WINDOWS" to 227, "LEFT_GUI" to 227, "LEFT_META" to 227,
                "RIGHT_CONTROL" to 228, "RIGHT_CTRL" to 228, "RIGHT_SHIFT" to 229, "RIGHT_ALT" to 230,
                "RIGHT_WINDOWS" to 231, "RIGHT_GUI" to 231, "RIGHT_META" to 231,
            )
        )
        for (i in 1..9) add("KEYPAD_$i" to 88 + i)
        add("KEYPAD_0" to 98)
    }
    private val KEY_BY_NAME = KEY_NAMES.toMap()
    /** First (canonical) name for each usage. */
    private val NAME_BY_KEY = KEY_NAMES.groupBy({ it.second }, { it.first }).mapValues { it.value.first() }

    fun keyUsage(name: String): Int? = KEY_BY_NAME[name.trim().uppercase().replace(' ', '_')]

    // ------------------------------------------------------------------ export

    /** Writes a Steam Input configuration for the Steam Controller (2015). */
    fun export(p: Profile): String {
        val root = VdfNode()
        val cm = root.node("controller_mappings")
        cm.add("version", "3")
        cm.add("revision", "1")
        cm.add("title", p.name)
        cm.add("description", "Exported from SC Mapper")
        cm.add("creator", "0")
        cm.add("progenitor", "")
        cm.add("url", "")
        cm.add("export_type", "personal_local")
        cm.add("controller_type", "controller_steamcontroller_gordon")
        cm.add("major_revision", "0")
        cm.add("minor_revision", "0")
        cm.add("Timestamp", (System.currentTimeMillis() / 1000).toString())
        cm.node("actions").node("Default").add("title", "Default").add("legacy_set", "1")
        cm.node("action_layers")

        val sources = ArrayList<Pair<String, Int>>()
        var nextId = 0
        fun group(source: String, mode: String, build: (inputs: VdfNode, settings: VdfNode) -> Unit) {
            val g = cm.node("group")
            val id = nextId++
            g.add("id", id.toString()).add("mode", mode)
            val inputs = g.node("inputs")
            val settings = VdfNode()
            build(inputs, settings)
            if (settings.entries.isNotEmpty()) g.add("settings", settings)
            sources += source to id
        }

        group("button_diamond", "four_buttons") { inputs, _ ->
            for ((name, btn) in listOf("button_a" to Btn.A, "button_b" to Btn.B, "button_x" to Btn.X, "button_y" to Btn.Y)) {
                buttonInput(inputs, name, p.binding(btn))
            }
        }
        group("switch", "switches") { inputs, _ ->
            for ((name, btn) in SWITCHES) buttonInput(inputs, name, p.binding(btn))
        }
        if (listOf(Btn.DPAD_UP, Btn.DPAD_DOWN, Btn.DPAD_LEFT, Btn.DPAD_RIGHT).any { !p.binding(it).press.isNone }) {
            group("dpad", "dpad") { inputs, _ ->
                buttonInput(inputs, "dpad_north", p.binding(Btn.DPAD_UP))
                buttonInput(inputs, "dpad_south", p.binding(Btn.DPAD_DOWN))
                buttonInput(inputs, "dpad_west", p.binding(Btn.DPAD_LEFT))
                buttonInput(inputs, "dpad_east", p.binding(Btn.DPAD_RIGHT))
            }
        }
        for ((source, t) in listOf("left_trigger" to p.leftTrigger, "right_trigger" to p.rightTrigger)) {
            group(source, "trigger") { inputs, settings ->
                actionInput(inputs, "click", t.fullAction)
                actionInput(inputs, "edge", t.softAction)
                settings.add("output_trigger", when (t.analog) { TriggerTarget.NONE -> "0"; TriggerTarget.LEFT -> "1"; TriggerTarget.RIGHT -> "2" })
            }
        }
        surfaceGroup(::group, "joystick", p.stick, p.binding(Btn.STICK_CLICK).press)
        if (p.rightStick.mode != SurfaceMode.NONE) surfaceGroup(::group, "right_joystick", p.rightStick, p.binding(Btn.RSTICK_CLICK).press)
        surfaceGroup(::group, "left_trackpad", p.leftPad, p.leftPad.clickAction)
        surfaceGroup(::group, "right_trackpad", p.rightPad, p.rightPad.clickAction)
        if (p.gyro.mode != GyroMode.OFF) {
            group("gyro", if (p.gyro.mode == GyroMode.MOUSE) "absolute_mouse" else "joystick_camera") { _, _ -> }
        }

        val preset = cm.node("preset")
        preset.add("id", "0").add("name", "Default")
        val gsb = preset.node("group_source_bindings")
        for ((source, id) in sources) gsb.add(id.toString(), "$source active")
        cm.node("settings")
        return Vdf.write(root)
    }

    private fun surfaceGroup(
        group: (String, String, (VdfNode, VdfNode) -> Unit) -> Unit,
        source: String, s: SurfaceConfig, click: Action,
    ) {
        val mode = when (s.mode) {
            SurfaceMode.NONE -> "single_button"
            SurfaceMode.JOYSTICK -> if (s.target == StickTarget.RIGHT && source != "right_joystick") "joystick_camera" else "joystick_move"
            SurfaceMode.DIRECTIONAL -> "dpad"
            SurfaceMode.GRID -> "touch_menu"
            SurfaceMode.MOUSE -> if (source.endsWith("joystick")) "joystick_mouse" else "absolute_mouse"
            SurfaceMode.SCROLL -> "scrollwheel"
        }
        group(source, mode) { inputs, settings ->
            actionInput(inputs, "click", click)
            when (s.mode) {
                SurfaceMode.DIRECTIONAL -> {
                    for ((i, name) in listOf("dpad_north", "dpad_south", "dpad_west", "dpad_east").withIndex()) actionInput(inputs, name, s.dirActions[i])
                    if (!source.endsWith("joystick")) settings.add("requires_click", if (s.requireClick) "1" else "0")
                }
                SurfaceMode.GRID -> {
                    val n = s.rows * s.cols
                    for (i in 0 until n) actionInput(inputs, "touch_menu_button_$i", s.gridActions[i])
                    settings.add("touch_menu_button_count", n.toString())
                }
                SurfaceMode.SCROLL -> {
                    actionInput(inputs, "scroll_clockwise", s.scrollCw)
                    actionInput(inputs, "scroll_counterclockwise", s.scrollCcw)
                }
                SurfaceMode.JOYSTICK -> settings.add("output_joystick", if (s.target == StickTarget.RIGHT) "1" else "0")
                else -> {}
            }
        }
    }

    private fun buttonInput(inputs: VdfNode, name: String, b: ButtonBinding) {
        if (b.press.isNone && b.hold.isNone) return
        val input = inputs.node(name)
        val activators = input.node("activators")
        if (!b.press.isNone) bindingsNode(activators.node("Full_Press"), b.press)
        if (!b.hold.isNone) bindingsNode(activators.node("Long_Press"), b.hold)
        input.node("disabled_activators")
    }

    private fun actionInput(inputs: VdfNode, name: String, a: Action) {
        if (a.isNone) return
        val input = inputs.node(name)
        bindingsNode(input.node("activators").node("Full_Press"), a)
        input.node("disabled_activators")
    }

    private fun bindingsNode(activator: VdfNode, a: Action) {
        val bindings = activator.node("bindings")
        for (line in actionToBindings(a)) bindings.add("binding", "$line, , ")
    }

    fun actionToBindings(a: Action): List<String> = when (a.type) {
        ActionType.NONE -> emptyList()
        ActionType.GAMEPAD -> {
            val o = G.entries[a.code]
            listOfNotNull(XINPUT.entries.firstOrNull { it.value == o }?.key?.let { "xinput_button $it" })
        }
        ActionType.KEY -> buildList {
            for ((bit, name) in listOf(Keys.MOD_CTRL to "LEFT_CONTROL", Keys.MOD_SHIFT to "LEFT_SHIFT", Keys.MOD_ALT to "LEFT_ALT", Keys.MOD_META to "LEFT_WINDOWS")) {
                if (a.mods and bit != 0) add("key_press $name")
            }
            NAME_BY_KEY[a.code]?.let { add("key_press $it") }
        }
        ActionType.MOUSE -> listOf("mouse_button " + MouseOut.entries[a.code].name)
        ActionType.WHEEL -> listOf("mouse_wheel " + when (WheelOut.entries[a.code]) {
            WheelOut.UP -> "SCROLL_UP"
            WheelOut.DOWN -> "SCROLL_DOWN"
            WheelOut.LEFT -> "SCROLL_LEFT"
            WheelOut.RIGHT -> "SCROLL_RIGHT"
        })
        // Media keys and SC Mapper shortcuts have no Steam Input equivalent.
        ActionType.MEDIA, ActionType.SPECIAL -> emptyList()
    }
}
