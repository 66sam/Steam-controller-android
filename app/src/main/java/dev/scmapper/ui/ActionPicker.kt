package dev.scmapper.ui

import android.app.AlertDialog
import android.content.Context
import dev.scmapper.mapping.Action
import dev.scmapper.mapping.ActionType
import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.Keys
import dev.scmapper.mapping.MouseOut
import dev.scmapper.mapping.Special
import dev.scmapper.mapping.WheelOut

/** Two-step dialog: pick a category, then the output. Keys can get modifiers. */
object ActionPicker {
    fun show(context: Context, title: String, current: Action, onPick: (Action) -> Unit) {
        val categories = listOf(
            "None" to ActionType.NONE,
            "Gamepad button" to ActionType.GAMEPAD,
            "Keyboard key" to ActionType.KEY,
            "Mouse button" to ActionType.MOUSE,
            "Mouse wheel" to ActionType.WHEEL,
            "Media / Android keys" to ActionType.MEDIA,
            "Profile & controller" to ActionType.SPECIAL,
        )
        AlertDialog.Builder(context)
            .setTitle("$title · now: ${current.label()}")
            .setItems(categories.map { it.first }.toTypedArray()) { _, which ->
                when (categories[which].second) {
                    ActionType.NONE -> onPick(Action.NONE)
                    ActionType.GAMEPAD -> list(context, "Gamepad button", GamepadOut.entries.map { it.label }) { onPick(Action.gp(GamepadOut.entries[it])) }
                    ActionType.KEY -> list(context, "Keyboard key", Keys.keyboard.map { it.second }) { i ->
                        val usage = Keys.keyboard[i].first
                        if (Keys.isModifierKey(usage)) onPick(Action.key(usage))
                        else modifiers(context, current.takeIf { it.type == ActionType.KEY }?.mods ?: 0) { onPick(Action.key(usage, it)) }
                    }
                    ActionType.MOUSE -> list(context, "Mouse button", MouseOut.entries.map { it.label }) { onPick(Action.mouse(MouseOut.entries[it])) }
                    ActionType.WHEEL -> list(context, "Mouse wheel", WheelOut.entries.map { it.label }) { onPick(Action.wheel(WheelOut.entries[it])) }
                    ActionType.MEDIA -> list(context, "Media / Android", Keys.media.map { it.second }) { onPick(Action.media(Keys.media[it].first)) }
                    ActionType.SPECIAL -> list(context, "Profile & controller", Special.entries.map { it.label }) { onPick(Action.special(Special.entries[it])) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun list(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(items.toTypedArray()) { _, which -> onPick(which) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun modifiers(context: Context, initial: Int, onDone: (Int) -> Unit) {
        val names = arrayOf("Ctrl", "Shift", "Alt", "Meta")
        val bits = intArrayOf(Keys.MOD_CTRL, Keys.MOD_SHIFT, Keys.MOD_ALT, Keys.MOD_META)
        val checked = BooleanArray(4) { initial and bits[it] != 0 }
        AlertDialog.Builder(context)
            .setTitle("Hold modifiers too? (optional)")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onDone(bits.indices.filter { checked[it] }.fold(0) { acc, i -> acc or bits[i] })
            }
            .show()
    }
}
