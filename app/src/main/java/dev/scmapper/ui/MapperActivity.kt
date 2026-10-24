package dev.scmapper.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.scmapper.App
import dev.scmapper.controller.ControllerModel
import dev.scmapper.mapping.Action
import dev.scmapper.mapping.ActionType
import dev.scmapper.mapping.ControlPart
import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.GyroMode
import dev.scmapper.mapping.Keys
import dev.scmapper.mapping.MouseOut
import dev.scmapper.mapping.Profile
import dev.scmapper.mapping.Special
import dev.scmapper.mapping.StickTarget
import dev.scmapper.mapping.SurfaceConfig
import dev.scmapper.mapping.SurfaceMode
import dev.scmapper.mapping.TriggerTarget
import dev.scmapper.mapping.WheelOut
import dev.scmapper.service.BridgeService
import dev.scmapper.ui.ProfileEditorActivity as PE

/**
 * Steam-style visual mapper: a drawing of the controller showing what each control does. Tap a
 * control, or press it on the controller, to change it. "Quick assign" picks an output first and
 * then the button that should produce it.
 */
class MapperActivity : Activity() {
    private val app get() = application as App
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var form: Form
    private lateinit var view: ControllerView
    private lateinit var listForm: Form
    private lateinit var status: TextView
    private lateinit var profileName: String
    private lateinit var profile: Profile
    /** Null = follow the connected controller. */
    private var chosenModel: ControllerModel? = null
    private var listening = true
    private var dialog: DialogInterface? = null
    /** Set while "quick assign" waits for the button that should get this action. */
    private var pendingAssign: Action? = null

    private val poll = object : Runnable {
        override fun run() {
            val live = BridgeService.instance?.liveController()
            view.live = live?.second
            val model = chosenModel ?: live?.first ?: ControllerModel.SC2015
            if (model != view.model) {
                view.model = model
                renderList()
            }
            handler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileName = intent.getStringExtra(EXTRA_NAME) ?: app.profiles.activeName
        title = "Controller mapper"
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form = Form(this, content)
        val pad = form.dp(12)
        content.setPadding(pad, pad, pad, pad * 3)
        view = ControllerView(this)
        view.onPart = { edit(it) }
        status = TextView(this).apply { textSize = 14f; setPadding(pad / 2, pad / 2, pad / 2, pad / 2) }
        listForm = Form(this, LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        profile = app.profiles.get(profileName) ?: app.profiles.active().also { profileName = it.name }
        render()
        handler.post(poll)
        updateCapture()
    }

    override fun onPause() {
        handler.removeCallbacks(poll)
        BridgeService.instance?.setCapture(null)
        super.onPause()
    }

    private fun updateCapture() {
        val s = BridgeService.instance
        if (s == null) {
            status.text = "Start the service on the main screen to see your controller live."
            return
        }
        val wanted = listening || pendingAssign != null
        s.setCapture(if (wanted) ({ part -> onControllerPart(part) }) else null)
        status.text = when {
            pendingAssign != null -> "Now press the button that should do: ${pendingAssign!!.label()}"
            listening -> "Press or touch any control on your controller to edit it. (Controller output is paused on this screen.)"
            else -> "Controller output is live — try your mapping. Tap a control in the picture to edit it."
        }
    }

    private fun render() {
        val f = form
        f.clear()
        f.choice("Profile", app.profiles.names(), profileName, { it + if (it == app.profiles.activeName) "  (active)" else "" }) {
            profileName = it
            profile = app.profiles.get(it) ?: profile
            render()
        }
        if (profileName != app.profiles.activeName) f.button("Make \"$profileName\" the active profile") {
            app.profiles.activeName = profileName
            render()
        }
        val models = listOf<ControllerModel?>(null) + ControllerModel.entries
        f.choice("Layout", models, chosenModel, { it?.title ?: "Follow connected controller" }) {
            chosenModel = it
            render()
        }
        (view.parent as? LinearLayout)?.removeView(view)
        f.container.addView(view)
        view.labels = { shortLabel(it) }
        (status.parent as? LinearLayout)?.removeView(status)
        f.container.addView(status)
        f.check("Select controls by pressing them on the controller", listening) {
            listening = it
            updateCapture()
        }
        f.buttons(
            "Quick assign…" to { quickAssign() },
            "All settings" to {
                startActivity(Intent(this, PE::class.java).putExtra(PE.EXTRA_NAME, profileName))
            },
        )
        f.header("Controls")
        (listForm.container.parent as? LinearLayout)?.removeView(listForm.container)
        f.container.addView(listForm.container)
        renderList()
        updateCapture()
    }

    private fun partsFor(model: ControllerModel): List<ControlPart> = when (model) {
        ControllerModel.SC2015 -> listOf(
            ControlPart.A, ControlPart.B, ControlPart.X, ControlPart.Y, ControlPart.LB, ControlPart.RB,
            ControlPart.LT, ControlPart.RT, ControlPart.BACK, ControlPart.STEAM, ControlPart.START,
            ControlPart.LGRIP, ControlPart.RGRIP, ControlPart.LSTICK, ControlPart.STICK_CLICK,
            ControlPart.LPAD, ControlPart.RPAD, ControlPart.GYRO,
        )
        else -> ControlPart.entries.filter { !(model == ControllerModel.GENERIC && (it == ControlPart.LPAD || it == ControlPart.RPAD)) }
    }

    private fun renderList() {
        val f = listForm
        f.clear()
        for (part in partsFor(view.model)) {
            f.row(part.label, longLabel(part)) { edit(part) }
        }
    }

    private fun refresh() {
        profile = app.profiles.get(profileName) ?: profile
        view.labels = { shortLabel(it) }
        renderList()
    }

    private fun save() {
        app.profiles.save(profile)
        refresh()
    }

    // ------------------------------------------------------------------ editing

    private fun onControllerPart(part: ControlPart) {
        val action = pendingAssign
        if (action != null) {
            assign(part, action)
            return
        }
        // A dialog (or picker) is open: don't stack another one on top.
        if (dialog != null || !hasWindowFocus()) return
        view.selected = part
        edit(part)
    }

    private fun edit(part: ControlPart) {
        view.selected = part
        val section = when (part) {
            ControlPart.LT -> PE.SECTION_LT
            ControlPart.RT -> PE.SECTION_RT
            ControlPart.LSTICK -> PE.SECTION_LSTICK
            ControlPart.RSTICK -> PE.SECTION_RSTICK
            ControlPart.LPAD -> PE.SECTION_LPAD
            ControlPart.RPAD -> PE.SECTION_RPAD
            ControlPart.GYRO -> PE.SECTION_GYRO
            else -> null
        }
        if (section != null) {
            startActivity(Intent(this, PE::class.java).putExtra(PE.EXTRA_NAME, profileName).putExtra(PE.EXTRA_SECTION, section))
            return
        }
        val btn = part.btn ?: return
        val binding = profile.binding(btn)
        val items = arrayOf(
            "Action: ${binding.press.label()}",
            "Long-press action: ${binding.hold.label()}",
            "Turbo: ${if (binding.turbo) "on" else "off"}",
        )
        dialog = AlertDialog.Builder(this).setTitle(btn.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> ActionPicker.show(this, btn.label, binding.press) { binding.press = it; save() }
                    1 -> ActionPicker.show(this, "${btn.label} (long press)", binding.hold) { binding.hold = it; save() }
                    2 -> { binding.turbo = !binding.turbo; save() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { dialog = null }
            .show()
    }

    private fun quickAssign() {
        if (BridgeService.instance?.liveController() == null) {
            Toast.makeText(this, "Connect a controller first", Toast.LENGTH_SHORT).show()
            return
        }
        ActionPicker.show(this, "Quick assign: choose what the button should do", Action.NONE) { action ->
            pendingAssign = action
            updateCapture()
            dialog = AlertDialog.Builder(this)
                .setTitle("Press a button")
                .setMessage("Press the button on your controller that should do:\n\n${action.label()}")
                .setNegativeButton(android.R.string.cancel) { _, _ -> pendingAssign = null; updateCapture() }
                .setOnDismissListener { dialog = null }
                .show()
        }
    }

    private fun assign(part: ControlPart, action: Action) {
        val target = when {
            part.btn != null -> { profile.binding(part.btn).press = action; part.btn.label }
            part == ControlPart.LT -> { profile.leftTrigger.fullAction = action; "Left trigger (full pull)" }
            part == ControlPart.RT -> { profile.rightTrigger.fullAction = action; "Right trigger (full pull)" }
            part == ControlPart.LPAD -> { profile.leftPad.clickAction = action; "Left pad click" }
            part == ControlPart.RPAD -> { profile.rightPad.clickAction = action; "Right pad click" }
            else -> {
                Toast.makeText(this, "That's not a button — press a button instead", Toast.LENGTH_SHORT).show()
                return
            }
        }
        pendingAssign = null
        dialog?.dismiss()
        save()
        updateCapture()
        view.selected = part
        Toast.makeText(this, "$target → ${action.label()}", Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------ labels

    private fun shortLabel(part: ControlPart): String {
        val btn = part.btn
        if (btn != null) return shortAction(profile.binding(btn).press)
        return when (part) {
            ControlPart.LT -> triggerShort(profile.leftTrigger.analog, profile.leftTrigger.fullAction)
            ControlPart.RT -> triggerShort(profile.rightTrigger.analog, profile.rightTrigger.fullAction)
            ControlPart.LSTICK -> surfaceShort(profile.stick)
            ControlPart.RSTICK -> surfaceShort(profile.rightStick)
            ControlPart.LPAD -> surfaceShort(profile.leftPad)
            ControlPart.RPAD -> surfaceShort(profile.rightPad)
            ControlPart.GYRO -> when (profile.gyro.mode) {
                GyroMode.OFF -> "Gyro off"
                GyroMode.MOUSE -> "Gyro mouse"
                GyroMode.STICK -> "Gyro stick"
            }
            else -> part.short
        }
    }

    private fun longLabel(part: ControlPart): String {
        val btn = part.btn
        if (btn != null) {
            val b = profile.binding(btn)
            return b.press.label() + (if (!b.hold.isNone) "  ·  hold: ${b.hold.label()}" else "") + (if (b.turbo) "  ·  turbo" else "")
        }
        return when (part) {
            ControlPart.LT -> profile.leftTrigger.let { "${it.analog.label}; full pull: ${it.fullAction.label()}; soft pull: ${it.softAction.label()}" }
            ControlPart.RT -> profile.rightTrigger.let { "${it.analog.label}; full pull: ${it.fullAction.label()}; soft pull: ${it.softAction.label()}" }
            ControlPart.LSTICK -> surfaceLong(profile.stick)
            ControlPart.RSTICK -> surfaceLong(profile.rightStick)
            ControlPart.LPAD -> surfaceLong(profile.leftPad) + "; click: ${profile.leftPad.clickAction.label()}"
            ControlPart.RPAD -> surfaceLong(profile.rightPad) + "; click: ${profile.rightPad.clickAction.label()}"
            ControlPart.GYRO -> profile.gyro.let { if (it.mode == GyroMode.OFF) "Off" else "${it.mode.label} · ${it.activation.label}" }
            else -> ""
        }
    }

    private fun surfaceLong(s: SurfaceConfig): String = when (s.mode) {
        SurfaceMode.JOYSTICK -> "${s.mode.label}: ${s.target.label}"
        SurfaceMode.DIRECTIONAL -> "${s.mode.label}: " + s.dirActions.joinToString(" ") { shortAction(it) }
        SurfaceMode.GRID -> "${s.mode.label}: ${s.rows}×${s.cols}"
        else -> s.mode.label
    }

    private fun surfaceShort(s: SurfaceConfig): String = when (s.mode) {
        SurfaceMode.NONE -> "Off"
        SurfaceMode.JOYSTICK -> if (s.target == StickTarget.LEFT) "L stick" else "R stick"
        SurfaceMode.DIRECTIONAL -> {
            val a = s.dirActions
            when {
                a.all { it.type == ActionType.GAMEPAD && it.code >= GamepadOut.DPAD_UP.ordinal } -> "D-pad"
                a.map { it.code } == listOf(26, 22, 4, 7) -> "WASD"
                a.map { it.code } == listOf(82, 81, 80, 79) -> "Arrows"
                a.all { it.type == ActionType.GAMEPAD && it.code <= GamepadOut.Y.ordinal } -> "ABXY"
                else -> "Buttons"
            }
        }
        SurfaceMode.GRID -> "Grid ${s.rows}×${s.cols}"
        SurfaceMode.MOUSE -> "Mouse"
        SurfaceMode.SCROLL -> "Scroll"
    }

    private fun triggerShort(analog: TriggerTarget, full: Action): String = when {
        analog == TriggerTarget.LEFT -> "LT"
        analog == TriggerTarget.RIGHT -> "RT"
        else -> shortAction(full)
    }

    private fun shortAction(a: Action): String = when (a.type) {
        ActionType.NONE -> "—"
        ActionType.GAMEPAD -> when (GamepadOut.entries[a.code]) {
            GamepadOut.BACK -> "View"
            GamepadOut.START -> "Menu"
            GamepadOut.GUIDE -> "Guide"
            GamepadOut.L3 -> "L3"
            GamepadOut.R3 -> "R3"
            GamepadOut.DPAD_UP -> "↑"
            GamepadOut.DPAD_DOWN -> "↓"
            GamepadOut.DPAD_LEFT -> "←"
            GamepadOut.DPAD_RIGHT -> "→"
            GamepadOut.LT -> "LT"
            GamepadOut.RT -> "RT"
            else -> GamepadOut.entries[a.code].label
        }
        ActionType.KEY -> Keys.modsName(a.mods).replace("Ctrl+", "^").replace("Shift+", "⇧").replace("Alt+", "⌥") +
            Keys.keyName(a.code).replace(" Arrow", "").replace("Left ", "L").replace("Right ", "R")
        ActionType.MOUSE -> when (MouseOut.entries[a.code]) {
            MouseOut.LEFT -> "LMB"
            MouseOut.RIGHT -> "RMB"
            MouseOut.MIDDLE -> "MMB"
            MouseOut.BACK -> "M4"
            MouseOut.FORWARD -> "M5"
        }
        ActionType.WHEEL -> when (WheelOut.entries[a.code]) {
            WheelOut.UP -> "Wheel↑"
            WheelOut.DOWN -> "Wheel↓"
            WheelOut.LEFT -> "Wheel←"
            WheelOut.RIGHT -> "Wheel→"
        }
        ActionType.MEDIA -> Keys.mediaName(a.code).replace("Android ", "")
        ActionType.SPECIAL -> when (Special.entries[a.code]) {
            Special.NEXT_PROFILE -> "Next"
            Special.PREV_PROFILE -> "Prev"
            Special.PROFILE_MENU -> "Menu"
            Special.TOGGLE_GYRO -> "Gyro"
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}
