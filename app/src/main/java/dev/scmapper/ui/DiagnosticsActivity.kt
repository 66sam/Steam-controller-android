package dev.scmapper.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.scmapper.DiagLog
import dev.scmapper.service.BridgeService

/** Shows [DiagLog] so a problem can be copied and reported. */
class DiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private var lastText = ""

    private val update = object : Runnable {
        override fun run() {
            val t = report()
            if (t != lastText) {
                val atBottom = !scroll.canScrollVertically(1)
                lastText = t
                text.text = t
                if (atBottom) scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Diagnostics"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val form = Form(this, root)
        val pad = form.dp(8)
        root.setPadding(pad, pad, pad, pad)
        form.note("If something doesn't work, reproduce it (e.g. reconnect the controller and press some buttons), then copy this log.")
        form.buttons(
            "Copy" to {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SC Mapper log", report()))
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
            },
            "Share" to {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report()), "Share log"))
            },
            "Clear" to { DiagLog.clear() },
        )
        form.button("Rescan USB / reconnect") {
            BridgeService.instance?.rescanUsb()
        }
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this).apply { addView(text) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun report(): String {
        val s = BridgeService.instance
        val header = buildString {
            append("Service: ").append(if (s == null) "stopped" + (BridgeService.lastStartError?.let { " ($it)" } ?: "") else "running")
            if (s != null) {
                append("\nShizuku helper: ").append(s.devices.status).append(s.devices.lastError?.let { " — $it" } ?: "")
                val c = s.controllers()
                append("\nControllers: ").append(if (c.isEmpty()) "none" else c.joinToString { "${it.label} (player ${it.slot + 1})" })
            }
        }
        return header + "\n\n" + DiagLog.text()
    }

    override fun onResume() {
        super.onResume()
        handler.post(update)
    }

    override fun onPause() {
        handler.removeCallbacks(update)
        super.onPause()
    }
}
