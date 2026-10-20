package dev.scmapper.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import dev.scmapper.service.BridgeService

/** Live view of a controller's raw input, handy for checking pads, gyro and buttons. */
class MonitorActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val update = object : Runnable {
        override fun run() {
            val id = intent.getStringExtra(EXTRA_ID)
            text.text = id?.let { BridgeService.instance?.stateText(it) } ?: "Controller not connected."
            handler.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Live input"
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 16f
            setPadding(40, 40, 40, 40)
        }
        setContentView(text)
    }

    override fun onResume() {
        super.onResume()
        handler.post(update)
    }

    override fun onPause() {
        handler.removeCallbacks(update)
        super.onPause()
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
