package dev.scmapper.controller

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dev.scmapper.Settings
import dev.scmapper.output.VirtualDevices

/**
 * Any other gamepad Android knows about (Xbox, PlayStation, Switch, 8BitDo, ...).
 *
 * Its raw input is read through the Shizuku helper and the device is grabbed, so games only see
 * SC Mapper's virtual devices and remapping applies to it like to a Steam Controller. Users opt in
 * per controller because a grabbed controller disappears from every other app while in use.
 */
class GenericControllerManager(
    private val devices: VirtualDevices,
    private val settings: Settings,
    private val listener: ControllerListener,
) {
    private val thread = HandlerThread("generic").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile var available: List<EvdevDevice> = emptyList()
        private set
    @Volatile var lastError: String? = null
        private set

    private val open = HashMap<String, GenericLink>()
    private val byEvdevId = HashMap<Int, GenericLink>()
    private var nextId = 100

    /** Raw event observer for the setup wizard. */
    @Volatile var rawObserver: ((link: GenericLink, events: IntArray) -> Unit)? = null

    inner class GenericLink(val device: EvdevDevice, val evdevId: Int) :
        ControllerLink("evdev:${device.key}", device.name, ControllerModel.GENERIC, isBle = false) {
        val translator = EvdevTranslator(device, settings.genericMapping(device.key) ?: GenericMapping.defaults(device))
        var motion: EvdevDevice? = null
        var motionId = -1
        @Volatile var connected = false

        override fun sendFeature(msg: ByteArray) = false
    }

    init {
        devices.onEvdev = { id, events -> onEvents(id, events) }
        devices.onEvdevClosed = { id -> handler.post { onClosed(id) } }
    }

    /** Re-reads the device list and opens/closes controllers to match the user's choices. */
    fun refresh() {
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 300)
    }

    private val refreshRunnable = Runnable { doRefresh() }

    fun stop() {
        handler.post {
            for (link in open.values.toList()) closeLink(link)
        }
        thread.quitSafely()
    }

    /** Applies a changed mapping (from the setup wizard) to the running controller. */
    fun reloadMapping(key: String) = handler.post {
        val link = open[key] ?: return@post
        link.translator.mapping = settings.genericMapping(key) ?: GenericMapping.defaults(link.device)
    }

    fun link(key: String): GenericLink? = synchronized(open) { open[key] }

    private fun doRefresh() {
        if (!devices.isReady) return
        val text = devices.listInputDevices() ?: return
        val all = Evdev.parse(text)
        // Leave out SC Mapper's own virtual devices and Steam Controllers (handled natively).
        val usable = all.filter { it.isGamepad && !it.location.startsWith("scmapper/") && it.vendor != UsbControllerManager.VENDOR_VALVE }
        available = usable
        val wanted = settings.genericDevices
        val present = usable.associateBy { it.key }
        synchronized(open) {
            for ((key, link) in open.toMap()) {
                val d = present[key]
                if (key !in wanted || d == null || d.path != link.device.path) closeLink(link)
            }
            for (key in wanted) {
                if (key in open) continue
                val d = present[key] ?: continue
                openLink(d, all)
            }
        }
    }

    private fun openLink(d: EvdevDevice, all: List<EvdevDevice>) {
        val link = GenericLink(d, nextId++)
        val error = devices.openEvdev(link.evdevId, d.path, grab = true)
        if (error != null) {
            Log.w(TAG, "Cannot open ${d.name}: $error")
            lastError = "${d.name}: $error"
            return
        }
        lastError = null
        open[d.key] = link
        byEvdevId[link.evdevId] = link
        // Pair the controller's motion sensor device, if the driver created one.
        val motion = all.firstOrNull {
            it.isMotionSensor && ((d.uniq.isNotEmpty() && it.uniq == d.uniq) || it.name.startsWith(d.name))
        }
        if (motion != null) {
            val id = nextId++
            if (devices.openEvdev(id, motion.path, grab = true) == null) {
                link.motion = motion
                link.motionId = id
                byEvdevId[id] = link
            }
        }
        link.connected = true
        listener.onControllerConnected(link)
    }

    private fun closeLink(link: GenericLink) {
        open.remove(link.device.key)
        byEvdevId.remove(link.evdevId)
        devices.closeEvdev(link.evdevId)
        if (link.motionId >= 0) {
            byEvdevId.remove(link.motionId)
            devices.closeEvdev(link.motionId)
        }
        if (link.connected) {
            link.connected = false
            listener.onControllerDisconnected(link)
        }
    }

    private fun onEvents(id: Int, events: IntArray) {
        val link = synchronized(open) { byEvdevId[id] } ?: return
        if (id == link.motionId) {
            link.motion?.let { link.translator.applyMotion(events, it, link.state) }
        } else {
            link.translator.apply(events, link.state)
            rawObserver?.invoke(link, events)
        }
        listener.onControllerState(link)
    }

    private fun onClosed(id: Int) {
        val link = synchronized(open) { byEvdevId[id] } ?: return
        synchronized(open) { closeLink(link) }
        refresh()
    }

    companion object {
        private const val TAG = "GenericControllers"
    }
}
