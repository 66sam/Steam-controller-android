package dev.scmapper.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.scmapper.mapping.Action
import kotlin.math.roundToInt

/** Tiny programmatic form builder, so the app needs no UI libraries. */
class Form(val context: Context, val container: LinearLayout) {
    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).roundToInt()

    private val accent by lazy {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)
        tv.data
    }

    fun clear() = container.removeAllViews()

    fun header(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent)
        setPadding(dp(4), dp(20), dp(4), dp(6))
        container.addView(this)
    }

    fun subheader(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(4), dp(12), dp(4), dp(2))
        container.addView(this)
    }

    fun note(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        alpha = 0.8f
        setPadding(dp(4), dp(4), dp(4), dp(4))
        container.addView(this)
    }

    fun button(text: String, onClick: () -> Unit): Button = Button(context).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        container.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** A row of buttons sharing the width. */
    fun buttons(vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, action) in items) {
            row.addView(Button(context).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(row)
    }

    /** Two-line clickable row: title and current value. */
    fun row(title: String, value: String, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)?): View {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            val tv = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            setBackgroundResource(tv.resourceId)
            isClickable = onClick != null
            if (onClick != null) setOnClickListener { onClick() }
            if (onLongClick != null) setOnLongClickListener { onLongClick(); true }
        }
        layout.addView(TextView(context).apply { text = title; textSize = 16f })
        layout.addView(TextView(context).apply { text = value; textSize = 14f; alpha = 0.7f })
        container.addView(layout)
        return layout
    }

    fun <T> choice(title: String, options: List<T>, current: T, label: (T) -> String, onChange: (T) -> Unit) {
        row(title, label(current)) {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setSingleChoiceItems(options.map(label).toTypedArray(), options.indexOf(current)) { d, which ->
                    d.dismiss()
                    onChange(options[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    fun check(title: String, value: Boolean, onChange: (Boolean) -> Unit): CheckBox = CheckBox(context).apply {
        text = title
        isChecked = value
        textSize = 16f
        setPadding(dp(4), dp(6), dp(4), dp(6))
        setOnCheckedChangeListener { _, c -> onChange(c) }
        container.addView(this)
    }

    /** Slider for a float in [min, max] with the given step; [format] renders the value. */
    fun slider(title: String, min: Float, max: Float, step: Float, value: Float, format: (Float) -> String = { "%.2f".format(it) }, onChange: (Float) -> Unit) {
        val label = TextView(context).apply {
            textSize = 16f
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        fun show(v: Float) { label.text = "$title: ${format(v)}" }
        show(value)
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(context).apply {
            this.max = steps
            progress = ((value - min) / step).roundToInt().coerceIn(0, steps)
            setPadding(dp(16), dp(4), dp(16), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { show(min + p * step) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { onChange(min + s.progress * step) }
            })
        }
        container.addView(label)
        container.addView(bar)
    }

    fun action(title: String, action: Action, onChange: (Action) -> Unit) {
        row(title, action.label()) { ActionPicker.show(context, title, action, onChange) }
    }
}
