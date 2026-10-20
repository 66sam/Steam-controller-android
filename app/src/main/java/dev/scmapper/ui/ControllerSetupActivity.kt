package dev.scmapper.ui

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.scmapper.App
import dev.scmapper.controller.AxisRole
import dev.scmapper.controller.Evdev
import dev.scmapper.controller.GenericControllerManager
import dev.scmapper.controller.GenericMapping
import dev.scmapper.mapping.Btn
import dev.scmapper.service.BridgeService
import kotlin.math.abs

/**
 * Teaches SC Mapper the layout of a non-Steam controller: press each button / move each axis when
 * asked. Anything skipped keeps its current mapping.
 */
class ControllerSetupActivity : Activity() {
    private val app get() = application as App
    private lateinit var form: Form
    private lateinit var key: String
    private var link: GenericControllerManager.GenericLink? = null
    private lateinit var mapping: GenericMapping
    private var step = 0
    private var baseline: Map<Int, Int> = emptyMap()
    private var consumed = false

    private sealed class Step(val prompt: String) {
        class Button(prompt: String, val btn: Btn) : Step(prompt)
        class Axis(prompt: String, val role: AxisRole) : Step(prompt)
    }

    private val steps = listOf(
        Step.Button("Press the bottom face button (A / Cross)", Btn.A),
        Step.Button("Press the right face button (B / Circle)", Btn.B),
        Step.Button("Press the left face button (X / Square)", Btn.X),
        Step.Button("Press the top face button (Y / Triangle)", Btn.Y),
        Step.Button("Press the left bumper (LB / L1)", Btn.LB),
        Step.Button("Press the right bumper (RB / R1)", Btn.RB),
        Step.Button("Press Back / View / Select / Share / −", Btn.BACK),
        Step.Button("Press Start / Menu / Options / +", Btn.START),
        Step.Button("Press the Guide / Home / PS button (works as the Steam button)", Btn.STEAM),
        Step.Button("Click the left stick", Btn.STICK_CLICK),
        Step.Button("Click the right stick", Btn.RSTICK_CLICK),
        Step.Button("Press d-pad up", Btn.DPAD_UP),
        Step.Button("Press d-pad down", Btn.DPAD_DOWN),
        Step.Button("Press d-pad left", Btn.DPAD_LEFT),
        Step.Button("Press d-pad right", Btn.DPAD_RIGHT),
        Step.Axis("Push the left stick all the way right", AxisRole.LX),
        Step.Axis("Push the left stick all the way down", AxisRole.LY),
        Step.Axis("Push the right stick all the way right", AxisRole.RX),
        Step.Axis("Push the right stick all the way down", AxisRole.RY),
        Step.Axis("Pull the left trigger fully", AxisRole.LT),
        Step.Axis("Pull the right trigger fully", AxisRole.RT),
        Step.Button("Press the left back paddle (if any)", Btn.LGRIP),
        Step.Button("Press the right back paddle (if any)", Btn.RGRIP),
        Step.Button("Press the second left back paddle (if any)", Btn.LGRIP2),
        Step.Button("Press the second right back paddle (if any)", Btn.RGRIP2),
        Step.Button("Press the capture / share / extra button (if any)", Btn.QAM),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = intent.getStringExtra(EXTRA_KEY) ?: run { finish(); return }
        title = "Set up controller"
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        form = Form(this, content)
        val pad = form.dp(16)
        content.setPadding(pad, pad, pad, pad)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        val service = BridgeService.instance
        link = service?.generic?.link(key)
        val l = link
        if (service == null || l == null) {
            form.clear()
            form.header("Controller not active")
            form.note("Turn this controller on in SC Mapper's main screen (\"Other controllers\") first, then come back.")
            return
        }
        mapping = GenericMapping.fromJson(l.translator.mapping.toJson())
        service.generic.rawObserver = { lk, events -> if (lk === l) runOnUiThread { onRaw(events) } }
        startStep(step)
    }

    override fun onPause() {
        BridgeService.instance?.generic?.rawObserver = null
        super.onPause()
    }

    private fun startStep(i: Int) {
        step = i
        consumed = false
        baseline = HashMap(link?.translator?.lastAbs ?: emptyMap<Int, Int>())
        render()
    }

    private fun render() {
        val f = form
        f.clear()
        f.header(link?.device?.name ?: "Controller")
        if (step >= steps.size) {
            f.note("All done. Your layout is saved and already active.")
            f.button("Finish") { finish() }
            f.button("Start over") { startStep(0) }
            return
        }
        f.note("Step ${step + 1} of ${steps.size}")
        f.subheader(steps[step].prompt)
        f.buttons(
            "Back" to { if (step > 0) startStep(step - 1) },
            "Skip" to { startStep(step + 1) },
        )
        f.buttons(
            "Save and finish" to { save(); finish() },
            "Reset to defaults" to {
                link?.let {
                    app.settings.setGenericMapping(key, null)
                    BridgeService.instance?.generic?.reloadMapping(key)
                    mapping = GenericMapping.defaults(it.device)
                    Toast.makeText(this, "Default layout restored", Toast.LENGTH_SHORT).show()
                    startStep(0)
                }
            },
        )
    }

    private fun onRaw(events: IntArray) {
        if (consumed || step >= steps.size) return
        val device = link?.device ?: return
        val s = steps[step]
        var i = 0
        while (i + 2 < events.size) {
            val type = events[i]; val code = events[i + 1]; val value = events[i + 2]
            i += 3
            when (s) {
                is Step.Button -> {
                    if (type == Evdev.EV_KEY && value == 1) {
                        mapping.buttons.entries.removeAll { it.value == s.btn || it.key == code }
                        mapping.buttons[code] = s.btn
                        return advance()
                    }
                    // A hat switch answers the d-pad steps all at once.
                    if (type == Evdev.EV_ABS && value != 0 && s.btn.name.startsWith("DPAD") && (code == Evdev.ABS_HAT0X || code == Evdev.ABS_HAT0Y)) {
                        mapping.axes[Evdev.ABS_HAT0X] = AxisRole.HAT_X
                        mapping.axes[Evdev.ABS_HAT0Y] = AxisRole.HAT_Y
                        mapping.buttons.entries.removeAll { it.value.name.startsWith("DPAD") }
                        consumed = true
                        val next = steps.indexOfFirst { it is Step.Axis }
                        return startStep(next)
                    }
                }
                is Step.Axis -> {
                    if (type != Evdev.EV_ABS || code == Evdev.ABS_HAT0X || code == Evdev.ABS_HAT0Y) continue
                    val info = device.abs[code] ?: continue
                    val span = (info.max - info.min).coerceAtLeast(1).toDouble()
                    val start = baseline[code] ?: if (s.role == AxisRole.LT || s.role == AxisRole.RT) info.min else (info.min + info.max) / 2
                    val delta = (value - start) / span
                    if (abs(delta) < 0.4) continue
                    mapping.axes.entries.removeAll { it.value == s.role }
                    mapping.axes[code] = s.role
                    // Right / down / pull should increase the value; otherwise the axis is inverted.
                    if (delta < 0) mapping.inverted += code else mapping.inverted -= code
                    return advance()
                }
            }
        }
    }

    private fun advance() {
        consumed = true
        save()
        // Give the user a moment to release before listening for the next input.
        form.container.postDelayed({ startStep(step + 1) }, 350)
    }

    private fun save() {
        app.settings.setGenericMapping(key, mapping)
        BridgeService.instance?.generic?.reloadMapping(key)
    }

    companion object {
        const val EXTRA_KEY = "key"
    }
}
