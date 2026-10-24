package dev.scmapper.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.scmapper.App
import dev.scmapper.mapping.Action
import dev.scmapper.mapping.Btn
import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.GyroActivation
import dev.scmapper.mapping.GyroAxis
import dev.scmapper.mapping.GyroConfig
import dev.scmapper.mapping.GyroMode
import dev.scmapper.mapping.Profile
import dev.scmapper.mapping.SteamInputVdf
import dev.scmapper.mapping.StickTarget
import dev.scmapper.mapping.SurfaceConfig
import dev.scmapper.mapping.SurfaceMode
import dev.scmapper.mapping.TriggerConfig
import dev.scmapper.mapping.TriggerTarget

/** Edits one profile. Every change is saved immediately and applied live. */
class ProfileEditorActivity : Activity() {
    private val app get() = application as App
    private lateinit var profile: Profile
    private lateinit var form: Form
    private lateinit var scroll: ScrollView
    private var exportVdf = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_NAME)
        profile = name?.let { app.profiles.get(it) } ?: run {
            finish()
            return
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form = Form(this, content)
        val pad = form.dp(12)
        content.setPadding(pad, pad, pad, pad * 3)
        scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
        render()
    }

    private fun save(rerender: Boolean = false) {
        app.profiles.save(profile)
        if (rerender) render()
    }

    private fun render() {
        val y = scroll.scrollY
        title = profile.name
        val f = form
        f.clear()
        intent.getStringExtra(EXTRA_SECTION)?.let { section ->
            renderSection(section)
            scroll.post { scroll.scrollTo(0, y) }
            return
        }
        f.header(profile.name)
        val active = app.profiles.activeName == profile.name
        f.note(if (active) "This profile is active — changes apply immediately." else "Not active.")
        f.buttons(
            (if (active) "Active ✓" else "Activate") to { app.profiles.activeName = profile.name; render() },
            "Rename" to { rename() },
        )
        f.buttons(
            "Export…" to { export() },
            "Delete" to { delete() },
        )

        f.header("Buttons")
        f.note(
            "Tap a button to set what it does. A long-press action makes a short press tap the normal action instead. " +
                "Inputs your controller doesn't have are simply ignored."
        )
        for (b in Btn.entries.filter { it.bindable }) {
            val binding = profile.binding(b)
            val extra = buildString {
                if (!binding.hold.isNone) append("  ·  hold: ").append(binding.hold.label())
                if (binding.turbo) append("  ·  turbo")
            }
            f.row(b.label, binding.press.label() + extra) { editButton(b) }
        }

        f.header("Triggers")
        trigger("Left trigger", profile.leftTrigger)
        trigger("Right trigger", profile.rightTrigger)

        f.header("Left stick")
        surface(profile.stick, isStick = true)
        f.header("Right stick (2026 and other gamepads)")
        surface(profile.rightStick, isStick = true)
        f.header("Left trackpad")
        surface(profile.leftPad, isStick = false)
        f.header("Right trackpad")
        surface(profile.rightPad, isStick = false)

        f.header("Gyro")
        gyro(profile.gyro)

        f.header("Feedback")
        f.slider("Haptic strength", 0f, 100f, 5f, profile.hapticStrength.toFloat(), { "${it.toInt()}%" }) {
            profile.hapticStrength = it.toInt(); save()
        }
        f.check("Game rumble on the trackpads (needs the Xbox Wireless virtual controller type)", profile.rumble) {
            profile.rumble = it; save()
        }
        f.slider("Steam logo LED brightness", 0f, 100f, 5f, profile.ledBrightness.toFloat(), { "${it.toInt()}%" }) {
            profile.ledBrightness = it.toInt(); save()
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    /** Just one part of the profile, opened from the visual mapper. */
    private fun renderSection(section: String) {
        val f = form
        when (section) {
            SECTION_LT -> { f.header("Left trigger"); trigger("", profile.leftTrigger) }
            SECTION_RT -> { f.header("Right trigger"); trigger("", profile.rightTrigger) }
            SECTION_LSTICK -> { f.header("Left stick"); surface(profile.stick, isStick = true) }
            SECTION_RSTICK -> { f.header("Right stick"); surface(profile.rightStick, isStick = true) }
            SECTION_LPAD -> { f.header("Left trackpad"); surface(profile.leftPad, isStick = false) }
            SECTION_RPAD -> { f.header("Right trackpad"); surface(profile.rightPad, isStick = false) }
            SECTION_GYRO -> { f.header("Gyro"); gyro(profile.gyro) }
        }
        f.note("Changes are saved and applied immediately.")
        f.button("Done") { finish() }
    }

    private fun editButton(b: Btn) {
        val binding = profile.binding(b)
        val items = arrayOf(
            "Action: ${binding.press.label()}",
            "Long-press action: ${binding.hold.label()}",
            "Turbo: ${if (binding.turbo) "on" else "off"}",
        )
        AlertDialog.Builder(this).setTitle(b.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ActionPicker.show(this, b.label, binding.press) { binding.press = it; save(true) }
                    1 -> ActionPicker.show(this, "${b.label} (long press)", binding.hold) { binding.hold = it; save(true) }
                    2 -> { binding.turbo = !binding.turbo; save(true) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun trigger(label: String, t: TriggerConfig) {
        val f = form
        if (label.isNotEmpty()) f.subheader(label)
        f.choice("Analog output", TriggerTarget.entries.toList(), t.analog, { it.label }) { t.analog = it; save(true) }
        f.action("Soft pull action", t.softAction) { t.softAction = it; save(true) }
        if (!t.softAction.isNone) {
            f.slider("Soft pull point", 0.05f, 0.95f, 0.05f, t.softThreshold, { "${(it * 100).toInt()}%" }) { t.softThreshold = it; save() }
        }
        f.action("Full pull (click) action", t.fullAction) { t.fullAction = it; save(true) }
        f.slider("Deadzone", 0f, 0.3f, 0.01f, t.deadzone, { "${(it * 100).toInt()}%" }) { t.deadzone = it; save() }
    }

    private fun surface(s: SurfaceConfig, isStick: Boolean) {
        val f = form
        val modes = if (isStick) listOf(SurfaceMode.NONE, SurfaceMode.JOYSTICK, SurfaceMode.DIRECTIONAL, SurfaceMode.MOUSE)
        else SurfaceMode.entries.toList()
        f.choice("Mode", modes, s.mode, { it.label }) { s.mode = it; save(true) }
        val pct = { v: Float -> "${(v * 100).toInt()}%" }
        when (s.mode) {
            SurfaceMode.NONE -> {}
            SurfaceMode.JOYSTICK -> {
                f.choice("Acts as", StickTarget.entries.toList(), s.target, { it.label }) { s.target = it; save(true) }
                f.slider("Deadzone", 0f, 0.5f, 0.01f, s.deadzone, pct) { s.deadzone = it; save() }
                f.slider("Sensitivity", 0.5f, 3f, 0.05f, s.sensitivity, { "%.2f×".format(it) }) { s.sensitivity = it; save() }
                f.check("Invert Y", s.invertY) { s.invertY = it; save() }
                if (!isStick) f.check("Only while clicked", s.requireClick) { s.requireClick = it; save() }
            }
            SurfaceMode.DIRECTIONAL -> {
                f.note("The pad is split into up / down / left / right zones. Eight-way also presses two neighbours on the diagonals.")
                f.buttons(
                    "D-pad" to { fillDirs(s, GamepadOut.DPAD_UP, GamepadOut.DPAD_DOWN, GamepadOut.DPAD_LEFT, GamepadOut.DPAD_RIGHT) },
                    "ABXY" to { fillDirs(s, GamepadOut.Y, GamepadOut.A, GamepadOut.X, GamepadOut.B) },
                )
                f.buttons(
                    "WASD" to { s.dirActions = mutableListOf(Action.key(26), Action.key(22), Action.key(4), Action.key(7)); save(true) },
                    "Arrows" to { s.dirActions = mutableListOf(Action.key(82), Action.key(81), Action.key(80), Action.key(79)); save(true) },
                )
                val names = listOf("Up", "Down", "Left", "Right")
                for (i in 0 until 4) f.action(names[i], s.dirActions[i]) { s.dirActions[i] = it; save(true) }
                f.action("Center", s.centerAction) { s.centerAction = it; save(true) }
                f.check("Eight-way (diagonals)", s.eightWay) { s.eightWay = it; save() }
                f.slider(if (isStick) "Deadzone" else "Center size", 0f, 0.8f, 0.01f, s.deadzone, pct) { s.deadzone = it; save() }
                if (!isStick) f.check("Only while clicked", s.requireClick) { s.requireClick = it; save() }
            }
            SurfaceMode.GRID -> {
                f.note("The pad is split into a grid; each zone is its own button. Zone 1 is top-left.")
                f.choice("Rows", (1..4).toList(), s.rows, { it.toString() }) { s.rows = it; save(true) }
                f.choice("Columns", (1..4).toList(), s.cols, { it.toString() }) { s.cols = it; save(true) }
                if (s.rows == 2 && s.cols == 2) f.button("Fill as ABXY quadrants") {
                    s.gridActions[0] = Action.gp(GamepadOut.X); s.gridActions[1] = Action.gp(GamepadOut.Y)
                    s.gridActions[2] = Action.gp(GamepadOut.A); s.gridActions[3] = Action.gp(GamepadOut.B)
                    save(true)
                }
                for (r in 0 until s.rows) for (c in 0 until s.cols) {
                    val i = r * s.cols + c
                    f.action("Zone ${i + 1} (row ${r + 1}, column ${c + 1})", s.gridActions[i]) { s.gridActions[i] = it; save(true) }
                }
                f.check("Only while clicked", s.requireClick) { s.requireClick = it; save() }
            }
            SurfaceMode.MOUSE -> {
                f.slider("Sensitivity", 0.2f, 4f, 0.05f, s.sensitivity, { "%.2f×".format(it) }) { s.sensitivity = it; save() }
                if (isStick) {
                    f.slider("Deadzone", 0f, 0.5f, 0.01f, s.deadzone, pct) { s.deadzone = it; save() }
                } else {
                    f.check("Trackball (keeps rolling after a flick)", s.trackball) { s.trackball = it; save(true) }
                    if (s.trackball) f.slider("Trackball friction", 0.05f, 1f, 0.05f, s.friction, pct) { s.friction = it; save() }
                }
                f.check("Invert Y", s.invertY) { s.invertY = it; save() }
            }
            SurfaceMode.SCROLL -> {
                f.note("Circle your thumb around the pad.")
                f.action("Clockwise", s.scrollCw) { s.scrollCw = it; save(true) }
                f.action("Counter-clockwise", s.scrollCcw) { s.scrollCcw = it; save(true) }
                f.slider("Degrees per step", 10f, 90f, 5f, s.scrollStepDeg, { "${it.toInt()}°" }) { s.scrollStepDeg = it; save() }
            }
        }
        if (!isStick) {
            f.action("Click action", s.clickAction) { s.clickAction = it; save(true) }
            f.slider("Rotation", -45f, 45f, 1f, s.rotationDeg, { "${it.toInt()}°" }) { s.rotationDeg = it; save() }
            f.check("Haptic feedback", s.haptics) { s.haptics = it; save() }
        }
    }

    private fun fillDirs(s: SurfaceConfig, up: GamepadOut, down: GamepadOut, left: GamepadOut, right: GamepadOut) {
        s.dirActions = mutableListOf(Action.gp(up), Action.gp(down), Action.gp(left), Action.gp(right))
        save(true)
    }

    private fun gyro(g: GyroConfig) {
        val f = form
        f.choice("Mode", GyroMode.entries.toList(), g.mode, { it.label }) { g.mode = it; save(true) }
        if (g.mode == GyroMode.OFF) return
        if (g.mode == GyroMode.STICK) f.choice("Acts as", StickTarget.entries.toList(), g.target, { it.label }) { g.target = it; save(true) }
        f.choice("Activation", GyroActivation.entries.toList(), g.activation, { it.label }) { g.activation = it; save(true) }
        if (g.activation != GyroActivation.ALWAYS) {
            f.choice("Activation button", Btn.entries.toList(), g.button, { it.label }) { g.button = it; save(true) }
        }
        f.choice("Horizontal axis", GyroAxis.entries.toList(), g.horizontal, { it.label }) { g.horizontal = it; save(true) }
        f.slider("Horizontal sensitivity", 0.1f, 5f, 0.05f, g.sensX, { "%.2f×".format(it) }) { g.sensX = it; save() }
        f.slider("Vertical sensitivity", 0.1f, 5f, 0.05f, g.sensY, { "%.2f×".format(it) }) { g.sensY = it; save() }
        f.check("Invert horizontal", g.invertX) { g.invertX = it; save() }
        f.check("Invert vertical", g.invertY) { g.invertY = it; save() }
        if (g.mode == GyroMode.STICK) {
            f.slider("Anti-deadzone (game's own deadzone)", 0f, 0.5f, 0.01f, g.antiDeadzone, { "${(it * 100).toInt()}%" }) { g.antiDeadzone = it; save() }
        }
        f.slider("Ignore tiny movements below", 0f, 5f, 0.1f, g.smallRotation, { "%.1f°/s".format(it) }) { g.smallRotation = it; save() }
        f.note("Steam + Y toggles gyro, Steam + X recalibrates it.")
    }

    private fun export() {
        val formats = arrayOf("SC Mapper profile (.json) — keeps everything", "Steam Input configuration (.vdf) — best effort")
        AlertDialog.Builder(this).setTitle("Export as")
            .setItems(formats) { _, which ->
                exportVdf = which == 1
                val (mime, ext) = if (exportVdf) "application/octet-stream" to "vdf" else "application/json" to "json"
                startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(mime).putExtra(Intent.EXTRA_TITLE, "${profile.name}.$ext"),
                    REQ_EXPORT,
                )
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun rename() {
        val input = EditText(this).apply { setText(profile.name) }
        AlertDialog.Builder(this).setTitle("Rename").setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val new = input.text.toString().trim()
                if (app.profiles.rename(profile.name, new)) {
                    profile = app.profiles.get(new)!!
                    intent.putExtra(EXTRA_NAME, new)
                    render()
                } else {
                    Toast.makeText(this, "Name empty or already used", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun delete() {
        AlertDialog.Builder(this).setMessage("Delete \"${profile.name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                app.profiles.delete(profile.name)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == REQ_EXPORT && resultCode == RESULT_OK) {
            try {
                val text = if (exportVdf) SteamInputVdf.export(profile) else profile.toJson().toString(2)
                contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(text) }
                Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_SECTION = "section"
        const val SECTION_LT = "lt"
        const val SECTION_RT = "rt"
        const val SECTION_LSTICK = "lstick"
        const val SECTION_RSTICK = "rstick"
        const val SECTION_LPAD = "lpad"
        const val SECTION_RPAD = "rpad"
        const val SECTION_GYRO = "gyro"
        private const val REQ_EXPORT = 1
    }
}
