package dev.scmapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import dev.scmapper.controller.ControllerModel
import dev.scmapper.controller.RawState
import dev.scmapper.mapping.ControlPart
import dev.scmapper.mapping.ControlPart as P

/**
 * Drawing of a controller where every control can be tapped. Shows what each control is mapped to,
 * lights up controls as they're used and draws thumb positions on pads and sticks.
 * Shapes are laid out on a 100 × 62 grid and scaled to the view's width.
 */
class ControllerView(context: Context) : View(context) {
    private sealed class Shape {
        abstract fun contains(x: Float, y: Float): Boolean
        abstract val cx: Float
        abstract val cy: Float
        abstract val size: Float
    }

    private class Circle(override val cx: Float, override val cy: Float, val r: Float) : Shape() {
        override fun contains(x: Float, y: Float) = (x - cx) * (x - cx) + (y - cy) * (y - cy) <= (r + 1.5f) * (r + 1.5f)
        override val size get() = r * 2
    }

    private class Box(val l: Float, val t: Float, val r: Float, val b: Float, val radius: Float = 2f) : Shape() {
        override fun contains(x: Float, y: Float) = x in (l - 1)..(r + 1) && y in (t - 1)..(b + 1)
        override val cx get() = (l + r) / 2
        override val cy get() = (t + b) / 2
        override val size get() = minOf(r - l, b - t)
    }

    var model: ControllerModel = ControllerModel.SC2015
        set(value) { field = value; invalidate() }

    /** Text shown in each control (normally what it's mapped to). */
    var labels: (ControlPart) -> String = { it.short }
        set(value) { field = value; invalidate() }

    var live: RawState? = null
        set(value) { field = value; invalidate() }

    var selected: ControlPart? = null
        set(value) { field = value; invalidate() }

    var onPart: ((ControlPart) -> Unit)? = null

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accent: Int
    private val fg: Int

    init {
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, tv, true)
        accent = tv.data
        context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        fg = if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        isClickable = true
    }

    private fun shapes(): Map<ControlPart, Shape> = when (model) {
        ControllerModel.SC2015 -> mapOf(
            P.LT to Box(4f, 1f, 18f, 7f), P.LB to Box(19f, 1f, 33f, 7f),
            P.RB to Box(67f, 1f, 81f, 7f), P.RT to Box(82f, 1f, 96f, 7f),
            P.LPAD to Circle(27f, 24f, 11f), P.RPAD to Circle(73f, 24f, 11f),
            P.BACK to Circle(42.5f, 23f, 3f), P.STEAM to Circle(50f, 16f, 4.5f), P.START to Circle(57.5f, 23f, 3f),
            P.LSTICK to Circle(38f, 41f, 6f), P.STICK_CLICK to Circle(38f, 41f, 2.2f),
            P.Y to Circle(62f, 34f, 3f), P.X to Circle(56f, 40f, 3f), P.B to Circle(68f, 40f, 3f), P.A to Circle(62f, 46f, 3f),
            P.LGRIP to Box(13f, 53f, 31f, 59f), P.RGRIP to Box(69f, 53f, 87f, 59f),
            P.GYRO to Box(42f, 53f, 58f, 59f),
        )
        ControllerModel.SC2026 -> mapOf(
            P.LT to Box(4f, 1f, 18f, 7f), P.LB to Box(19f, 1f, 33f, 7f),
            P.RB to Box(67f, 1f, 81f, 7f), P.RT to Box(82f, 1f, 96f, 7f),
            P.LSTICK to Circle(20f, 18f, 6f), P.STICK_CLICK to Circle(20f, 18f, 2.2f),
            P.RSTICK to Circle(80f, 18f, 6f), P.RSTICK_CLICK to Circle(80f, 18f, 2.2f),
            P.DPAD_UP to Box(17.5f, 27f, 22.5f, 31.5f), P.DPAD_DOWN to Box(17.5f, 36.5f, 22.5f, 41f),
            P.DPAD_LEFT to Box(12.5f, 31.8f, 17.2f, 36.2f), P.DPAD_RIGHT to Box(22.8f, 31.8f, 27.5f, 36.2f),
            P.Y to Circle(80f, 28.5f, 3f), P.X to Circle(74.5f, 34f, 3f), P.B to Circle(85.5f, 34f, 3f), P.A to Circle(80f, 39.5f, 3f),
            P.LPAD to Box(32f, 24f, 47f, 42f, 3f), P.RPAD to Box(53f, 24f, 68f, 42f, 3f),
            P.BACK to Circle(40f, 15f, 2.6f), P.STEAM to Circle(50f, 13f, 3.8f), P.QAM to Circle(60f, 15f, 2.6f),
            P.GYRO to Box(43f, 45f, 57f, 50f),
            P.LGRIP to Box(6f, 53f, 21f, 59f), P.LGRIP2 to Box(22f, 53f, 37f, 59f),
            P.RGRIP2 to Box(63f, 53f, 78f, 59f), P.RGRIP to Box(79f, 53f, 94f, 59f),
        )
        ControllerModel.GENERIC -> mapOf(
            P.LT to Box(4f, 1f, 18f, 7f), P.LB to Box(19f, 1f, 33f, 7f),
            P.RB to Box(67f, 1f, 81f, 7f), P.RT to Box(82f, 1f, 96f, 7f),
            P.LSTICK to Circle(24f, 21f, 6.5f), P.STICK_CLICK to Circle(24f, 21f, 2.4f),
            P.RSTICK to Circle(63f, 38f, 6.5f), P.RSTICK_CLICK to Circle(63f, 38f, 2.4f),
            P.DPAD_UP to Box(34.5f, 30f, 39.5f, 34.5f), P.DPAD_DOWN to Box(34.5f, 39.5f, 39.5f, 44f),
            P.DPAD_LEFT to Box(29.5f, 34.8f, 34.2f, 39.2f), P.DPAD_RIGHT to Box(39.8f, 34.8f, 44.5f, 39.2f),
            P.Y to Circle(77f, 15f, 3.2f), P.X to Circle(71f, 21f, 3.2f), P.B to Circle(83f, 21f, 3.2f), P.A to Circle(77f, 27f, 3.2f),
            P.BACK to Circle(42f, 20f, 2.6f), P.STEAM to Circle(50f, 14f, 3.8f), P.START to Circle(58f, 20f, 2.6f),
            P.QAM to Circle(50f, 23f, 2.2f),
            P.GYRO to Box(43f, 46f, 57f, 51f),
            P.LGRIP to Box(6f, 53f, 21f, 59f), P.LGRIP2 to Box(22f, 53f, 37f, 59f),
            P.RGRIP2 to Box(63f, 53f, 78f, 59f), P.RGRIP to Box(79f, 53f, 94f, 59f),
        )
    }

    private fun bodyPath(scale: Float): Path = Path().apply {
        fun oval(l: Float, t: Float, r: Float, b: Float) = addOval(RectF(l * scale, t * scale, r * scale, b * scale), Path.Direction.CW)
        addRoundRect(RectF(5f * scale, 9f * scale, 95f * scale, 47f * scale), 14f * scale, 14f * scale, Path.Direction.CW)
        oval(5f, 28f, 40f, 61f)
        oval(60f, 28f, 95f, 61f)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 62f / 100f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / 100f
        val active = live?.let { ControlPart.active(it) }.orEmpty()
        bodyPaint.color = Color.argb(40, Color.red(fg), Color.green(fg), Color.blue(fg))
        canvas.drawPath(bodyPath(s), bodyPaint)

        val shapes = shapes()
        // Big shapes first so small ones (stick clicks) draw on top.
        for ((part, shape) in shapes.entries.sortedByDescending { it.value.size }) {
            val on = part in active
            fillPaint.color = if (on) accent else Color.argb(if (part == selected) 90 else 55, Color.red(fg), Color.green(fg), Color.blue(fg))
            strokePaint.color = if (part == selected) accent else Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg))
            strokePaint.strokeWidth = if (part == selected) 0.7f * s else 0.25f * s
            when (shape) {
                is Circle -> {
                    canvas.drawCircle(shape.cx * s, shape.cy * s, shape.r * s, fillPaint)
                    canvas.drawCircle(shape.cx * s, shape.cy * s, shape.r * s, strokePaint)
                }
                is Box -> {
                    val r = RectF(shape.l * s, shape.t * s, shape.r * s, shape.b * s)
                    canvas.drawRoundRect(r, shape.radius * s, shape.radius * s, fillPaint)
                    canvas.drawRoundRect(r, shape.radius * s, shape.radius * s, strokePaint)
                }
            }
        }
        drawLive(canvas, shapes, s)
        for ((part, shape) in shapes) {
            // Stick clicks sit inside the stick; their label would collide with the stick's.
            if (part == P.STICK_CLICK || part == P.RSTICK_CLICK) continue
            drawLabel(canvas, labels(part), shape, s, part in active)
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, shape: Shape, s: Float, on: Boolean) {
        val maxWidth = when (shape) {
            is Circle -> shape.r * 2.3f
            is Box -> shape.r - shape.l
        } * s
        textPaint.textSize = (if (shape.size >= 10) 3.2f else 2.4f) * s
        textPaint.color = if (on) Color.BLACK else fg
        val t = TextUtils.ellipsize(text, textPaint, maxOf(maxWidth, 8f * s), TextUtils.TruncateAt.END).toString()
        val dy = if (shape is Circle && shape.r >= 5f) -shape.r * 0.45f else 0f
        canvas.drawText(t, shape.cx * s, (shape.cy + dy) * s - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    /** Thumb positions on pads and stick deflection. */
    private fun drawLive(canvas: Canvas, shapes: Map<ControlPart, Shape>, s: Float) {
        val st = live ?: return
        dotPaint.color = accent
        fun dot(part: ControlPart, x: Int, y: Int, show: Boolean) {
            val shape = shapes[part] ?: return
            if (!show) return
            val half = when (shape) {
                is Circle -> shape.r
                is Box -> (shape.r - shape.l) / 2
            }
            val px = shape.cx + x / 32768f * half
            val py = shape.cy - y / 32768f * half
            canvas.drawCircle(px * s, py * s, 1.4f * s, dotPaint)
        }
        dot(P.LPAD, st.lpadX, st.lpadY, st.pressed(dev.scmapper.mapping.Btn.LPAD_TOUCH))
        dot(P.RPAD, st.rpadX, st.rpadY, st.pressed(dev.scmapper.mapping.Btn.RPAD_TOUCH))
        dot(P.LSTICK, st.stickX, st.stickY, st.stickX != 0 || st.stickY != 0)
        dot(P.RSTICK, st.rstickX, st.rstickY, st.rstickX != 0 || st.rstickY != 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val s = width / 100f
            val x = event.x / s
            val y = event.y / s
            // Smallest shape wins, so a stick click inside a stick is reachable.
            val hit = shapes().entries.filter { it.value.contains(x, y) }.minByOrNull { it.value.size }?.key
            if (hit != null) {
                performClick()
                onPart?.invoke(hit)
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
