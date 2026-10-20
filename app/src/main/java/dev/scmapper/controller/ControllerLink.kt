package dev.scmapper.controller

/** One physical Steam Controller, reachable over USB (wired or via the receiver) or Bluetooth LE. */
abstract class ControllerLink(val id: String, val label: String, val isBle: Boolean) {
    /** Last decoded input. Only touched by the transport's IO thread. */
    val state = RawState()

    /** Sends a feature message (see [SteamProtocol]); returns false if it could not be queued. */
    abstract fun sendFeature(msg: ByteArray): Boolean

    /** True while a previously sent message is still waiting to go out (BLE only). */
    open val busy: Boolean get() = false

    fun configure(gyro: Boolean, ledBrightness: Int) {
        sendFeature(SteamProtocol.clearDigitalMappings())
        sendFeature(SteamProtocol.configure(gyro, isBle))
        sendFeature(SteamProtocol.ledBrightness(ledBrightness))
    }

    fun haptic(right: Boolean, onUs: Int, offUs: Int, count: Int) {
        sendFeature(SteamProtocol.hapticPulse(right, onUs, offUs, count))
    }

    fun turnOff() {
        sendFeature(SteamProtocol.turnOff())
    }
}

interface ControllerListener {
    fun onControllerConnected(link: ControllerLink)

    /** Called on the transport's IO thread with [ControllerLink.state] already updated. */
    fun onControllerState(link: ControllerLink)

    fun onControllerDisconnected(link: ControllerLink)
}
