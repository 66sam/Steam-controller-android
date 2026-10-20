package dev.scmapper.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small on-screen profile picker drawn over the current app (needs "display over other apps").
 * It never takes focus, so the game underneath keeps running; it is driven by the controller.
 */
class OverlayMenu(private val context: Context, private val onSelect: (String) -> Unit, private val onClosed: () -> Unit) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private val rows = ArrayList<TextView>()
    private var items: List<String> = emptyList()
    private var selected = 0

    val isShowing get() = root != null

    fun canShow() = Settings.canDrawOverlays(context)

    fun show(names: List<String>, active: String) {
        if (isShowing || names.isEmpty()) return
        items = names
        selected = names.indexOf(active).coerceAtLeast(0)
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.argb(235, 23, 26, 33))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.argb(255, 102, 192, 244))
            }
        }
        layout.addView(TextView(context).apply {
            text = "Controller profile"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, dp(8))
        })
        rows.clear()
        for (name in names) {
            val row = TextView(context).apply {
                text = name
                textSize = 16f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            rows += row
            layout.addView(row)
        }
        layout.addView(TextView(context).apply {
            text = "Stick / left pad ↑↓ · A select · B close"
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(layout, params)
        root = layout
        highlight()
    }

    fun up() {
        if (items.isEmpty()) return
        selected = (selected - 1 + items.size) % items.size
        highlight()
    }

    fun down() {
        if (items.isEmpty()) return
        selected = (selected + 1) % items.size
        highlight()
    }

    fun select() {
        val name = items.getOrNull(selected)
        close()
        if (name != null) onSelect(name)
    }

    fun close() {
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        onClosed()
    }

    private fun highlight() {
        rows.forEachIndexed { i, row ->
            val on = i == selected
            row.setTextColor(if (on) Color.BLACK else Color.WHITE)
            row.background = if (on) GradientDrawable().apply {
                setColor(Color.rgb(102, 192, 244))
                cornerRadius = 12f
            } else null
        }
    }
}
