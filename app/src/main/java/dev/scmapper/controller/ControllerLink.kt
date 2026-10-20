package dev.scmapper.controller

import android.os.SystemClock

enum class ControllerModel(val title: String) {
    SC2015("Steam Controller (2015)"),
    SC2026("Steam Controller (2026)"),
    GENERIC("Gamepad"),
}

/**
 * One physical controller. Transports (USB, BLE, ...) implement the report primitives; this class
 * knows how each controller model wants to be configured and how to drive its haptics.
 */
abstract class ControllerLink(val id: String, val label: String, val model: ControllerModel, val isBle: Boolean) {
    /** Last decoded input. Only touched by the transport's IO thread. */
    val state = RawState()

    /** Sends a feature message without report id (see [Sc2015Protocol] / [Sc2026Protocol]). */
    protected abstract fun sendFeature(msg: ByteArray): Boolean

    /** Sends an output report, report id at index 0. */
    protected open fun sendOutput(report: ByteArray): Boolean = false

    /** True while previously sent messages are still waiting to go out (BLE only). */
    open val busy: Boolean get() = false

    private var lastLizardMs = 0L
    private var rumbleLow = 0f
    private var rumbleHigh = 0f
    private var lastRumbleMs = 0L

    open fun configure(gyro: Boolean, ledBrightness: Int) {
        when (model) {
            ControllerModel.SC2015 -> {
                sendFeature(Sc2015Protocol.clearDigitalMappings())
                sendFeature(Sc2015Protocol.configure(gyro, isBle))
                sendFeature(Sc2015Protocol.ledBrightness(ledBrightness))
            }
            ControllerModel.SC2026 -> {
                sendFeature(Sc2026Protocol.lizardOff())
                sendFeature(Sc2026Protocol.imu(gyro))
                lastLizardMs = SystemClock.uptimeMillis()
            }
            ControllerModel.GENERIC -> {}
        }
    }

    /** Short haptic pulse(s) on one side; [onUs] works as the strength. */
    open fun haptic(right: Boolean, onUs: Int, offUs: Int, count: Int) {
        when (model) {
            ControllerModel.SC2015 -> sendFeature(Sc2015Protocol.hapticPulse(right, onUs, offUs, count))
            ControllerModel.SC2026 -> sendOutput(Sc2026Protocol.hapticPulse(right, onUs, offUs, count))
            ControllerModel.GENERIC -> {}
        }
    }

    /**
     * Game rumble, each side 0..1 (left = strong / low frequency, right = weak / high frequency).
     * Called repeatedly while active and once with zeros to stop.
     */
    open fun rumble(low: Float, high: Float) {
        rumbleLow = low
        rumbleHigh = high
        lastRumbleMs = SystemClock.uptimeMillis()
        when (model) {
            ControllerModel.SC2015 -> {
                // No motors: approximate with a fast pulse train on each pad, "on" time sets the strength.
                if (busy) return
                if (low > 0.01f) haptic(false, (400 + 3600 * low).toInt(), 3500, 9)
                if (high > 0.01f) haptic(true, (300 + 2200 * high).toInt(), 2500, 12)
            }
            ControllerModel.SC2026 -> sendOutput(Sc2026Protocol.rumble((low * 65535).toInt(), (high * 65535).toInt()))
            ControllerModel.GENERIC -> {}
        }
    }

    open fun turnOff() {
        if (model == ControllerModel.SC2015) sendFeature(Sc2015Protocol.turnOff())
    }

    /** Called by the engine every few milliseconds. */
    open fun tick(nowMs: Long) {
        if (model != ControllerModel.SC2026) return
        if (nowMs - lastLizardMs >= Sc2026Protocol.LIZARD_RESEND_MS) {
            lastLizardMs = nowMs
            sendFeature(Sc2026Protocol.lizardOff())
        }
        if ((rumbleLow > 0f || rumbleHigh > 0f) && nowMs - lastRumbleMs >= Sc2026Protocol.RUMBLE_RESEND_MS) {
            rumble(rumbleLow, rumbleHigh)
        }
    }
}

interface ControllerListener {
    fun onControllerConnected(link: ControllerLink)

    /** Called on the transport's IO thread with [ControllerLink.state] already updated. */
    fun onControllerState(link: ControllerLink)

    fun onControllerDisconnected(link: ControllerLink)
}
