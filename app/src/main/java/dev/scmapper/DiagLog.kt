package dev.scmapper

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app log of what the bridge is doing (devices found, permissions, packets, errors), shown on the
 * Diagnostics screen so problems can be reported without adb.
 */
object DiagLog {
    private const val MAX_LINES = 400
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val onceKeys = HashSet<String>()

    @Synchronized
    fun log(message: String) {
        Log.i("SCMapper", message)
        lines.addLast(time.format(Date()) + "  " + message)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    /** Logs [message] only the first time [key] is seen (e.g. the first packet of each kind). */
    @Synchronized
    fun once(key: String, message: () -> String) {
        if (onceKeys.add(key)) log(message())
    }

    @Synchronized
    fun forget(keyPrefix: String) {
        onceKeys.removeAll { it.startsWith(keyPrefix) }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        onceKeys.clear()
    }

    fun hex(b: ByteArray, len: Int = b.size, max: Int = 24): String =
        (0 until minOf(len, max)).joinToString(" ") { "%02x".format(b[it].toInt() and 0xFF) } + if (len > max) " …" else ""
}
