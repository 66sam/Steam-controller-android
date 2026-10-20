package dev.scmapper.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Talks to Steam Controllers through the Android USB host API: the 2015 and 2026 controllers over
 * a cable, and their wireless receivers (one HID interface per controller slot).
 */
class UsbControllerManager(private val context: Context, private val listener: ControllerListener) {
    private val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val opened = HashMap<String, OpenDevice>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> open(device)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> close(device.deviceName)
                ACTION_PERMISSION -> if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device)
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        scan()
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        opened.keys.toList().forEach { close(it) }
    }

    /** Opens every supported device already plugged in, asking for permission where needed. */
    @Synchronized
    fun scan() {
        usb.deviceList.values.filter { kindOf(it) != null }.forEach { open(it) }
    }

    @Synchronized
    private fun open(device: UsbDevice) {
        val kind = kindOf(device) ?: return
        if (opened.containsKey(device.deviceName)) return
        if (!usb.hasPermission(device)) {
            val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            usb.requestPermission(device, PendingIntent.getBroadcast(context, device.deviceId, intent, flags))
            return
        }
        val connection = usb.openDevice(device) ?: run {
            Log.w(TAG, "openDevice failed for ${device.deviceName}")
            return
        }
        val open = OpenDevice(connection)
        var slot = 0
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (kind.dongle && kind.model == ControllerModel.SC2026 && iface.id !in 2..5) continue
            val epIn = endpoint(iface, UsbConstants.USB_DIR_IN) ?: continue
            if (!connection.claimInterface(iface, true)) {
                Log.w(TAG, "claimInterface ${iface.id} failed")
                continue
            }
            slot++
            val label = kind.model.title + if (kind.dongle) " · receiver slot $slot" else " · USB"
            val link = UsbLink(
                "usb:${device.deviceName}:${iface.id}", label, kind, open, connection, iface, epIn,
                endpoint(iface, UsbConstants.USB_DIR_OUT),
            )
            open.links += link
        }
        if (open.links.isEmpty()) {
            connection.close()
            return
        }
        opened[device.deviceName] = open
        open.links.forEach { it.startReading() }
    }

    @Synchronized
    private fun close(deviceName: String) {
        val open = opened.remove(deviceName) ?: return
        open.links.forEach { it.stopReading() }
        open.links.forEach { if (it.connected) listener.onControllerDisconnected(it) }
        open.connection.close()
    }

    /** A Valve HID interface: interrupt endpoint with 64 byte reports, not a boot keyboard/mouse. */
    private fun endpoint(iface: UsbInterface, dir: Int): UsbEndpoint? {
        if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) return null
        if (iface.interfaceProtocol == 1 || iface.interfaceProtocol == 2) return null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction == dir && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.maxPacketSize == 64) return ep
        }
        return null
    }

    private data class Kind(val model: ControllerModel, val dongle: Boolean)

    private class OpenDevice(val connection: UsbDeviceConnection) {
        val links = ArrayList<UsbLink>()
        /** For a cabled controller with several HID interfaces: the one that delivers input. */
        @Volatile var activeInterface = -1
    }

    private inner class UsbLink(
        id: String,
        label: String,
        private val kind: Kind,
        private val owner: OpenDevice,
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val epIn: UsbEndpoint,
        private val epOut: UsbEndpoint?,
    ) : ControllerLink(id, label, kind.model, isBle = false) {
        @Volatile var connected = false
        @Volatile private var running = false
        private var thread: Thread? = null

        override fun sendFeature(msg: ByteArray): Boolean {
            val (data, reportId) = when (model) {
                ControllerModel.SC2026 -> Sc2026Protocol.usbFeature(msg) to Sc2026Protocol.FEATURE_REPORT_ID
                else -> Sc2015Protocol.usbFeature(msg) to 0
            }
            // HID SET_REPORT(feature) on this interface.
            return connection.controlTransfer(0x21, 0x09, 0x0300 or reportId, iface.id, data, data.size, 250) >= 0
        }

        override fun sendOutput(report: ByteArray): Boolean {
            if (epOut != null) return connection.bulkTransfer(epOut, report, report.size, 100) >= 0
            // No interrupt OUT endpoint: HID SET_REPORT(output).
            val reportId = report[0].toInt() and 0xFF
            return connection.controlTransfer(0x21, 0x09, 0x0200 or reportId, iface.id, report, report.size, 100) >= 0
        }

        fun startReading() {
            running = true
            // A cabled 2015 controller has exactly one Valve interface; everything else waits for input.
            if (model == ControllerModel.SC2015 && !kind.dongle) markConnected()
            else if (model == ControllerModel.SC2015) sendFeature(Sc2015Protocol.clearDigitalMappings())
            thread = Thread({ readLoop() }, "usb-$id").apply { isDaemon = true; start() }
        }

        fun stopReading() {
            running = false
            thread?.interrupt()
        }

        private fun markConnected() {
            if (!connected) {
                connected = true
                listener.onControllerConnected(this)
            }
        }

        private fun markDisconnected() {
            if (connected) {
                connected = false
                listener.onControllerDisconnected(this)
            }
        }

        private fun readLoop() {
            val buf = ByteArray(64)
            var errors = 0
            while (running) {
                val n = connection.bulkTransfer(epIn, buf, buf.size, 500)
                if (n <= 0) {
                    // Timeouts are normal when idle; repeated hard errors mean the device is gone.
                    if (n < 0 && ++errors > 200) break
                    continue
                }
                errors = 0
                if (model == ControllerModel.SC2026) handle2026(buf, n) else handle2015(buf, n)
            }
        }

        private fun handle2015(buf: ByteArray, n: Int) {
            when (Sc2015Protocol.parseUsb(buf, n, state)) {
                Sc2015Protocol.REPORT_STATE -> {
                    markConnected()
                    listener.onControllerState(this)
                }
                Sc2015Protocol.REPORT_WIRELESS -> when (buf[4].toInt()) {
                    1 -> markDisconnected()
                    2 -> markConnected()
                }
            }
        }

        private fun handle2026(buf: ByteArray, n: Int) {
            when (Sc2026Protocol.parse(buf, n, state)) {
                Sc2026Protocol.Result.STATE -> {
                    if (!kind.dongle) {
                        // Only the first interface that reports state represents the controller.
                        if (owner.activeInterface == -1) owner.activeInterface = iface.id
                        if (owner.activeInterface != iface.id) return
                    }
                    markConnected()
                    listener.onControllerState(this)
                }
                Sc2026Protocol.Result.DISCONNECTED -> markDisconnected()
                else -> {}
            }
        }
    }

    companion object {
        private const val TAG = "UsbController"
        const val ACTION_PERMISSION = "dev.scmapper.USB_PERMISSION"
        const val VENDOR_VALVE = 0x28DE

        private fun kindOf(d: UsbDevice): Kind? {
            if (d.vendorId != VENDOR_VALVE) return null
            return when (d.productId) {
                Sc2015Protocol.PRODUCT_WIRED -> Kind(ControllerModel.SC2015, dongle = false)
                Sc2015Protocol.PRODUCT_DONGLE -> Kind(ControllerModel.SC2015, dongle = true)
                Sc2026Protocol.PRODUCT_WIRED -> Kind(ControllerModel.SC2026, dongle = false)
                Sc2026Protocol.PRODUCT_DONGLE_PROTEUS, Sc2026Protocol.PRODUCT_DONGLE_NEREID -> Kind(ControllerModel.SC2026, dongle = true)
                else -> null
            }
        }

        fun isSteamController(d: UsbDevice) = kindOf(d) != null

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
