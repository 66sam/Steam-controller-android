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
 * Talks to a wired Steam Controller or the wireless receiver through the Android USB host API.
 * The receiver exposes one HID interface per controller slot (up to four).
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
        usb.deviceList.values.filter { isSteamController(it) }.forEach { open(it) }
    }

    @Synchronized
    private fun open(device: UsbDevice) {
        if (!isSteamController(device) || opened.containsKey(device.deviceName)) return
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
        val dongle = device.productId == SteamProtocol.PRODUCT_DONGLE
        val open = OpenDevice(device, connection)
        var slot = 0
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            val ep = controllerEndpoint(iface) ?: continue
            if (!connection.claimInterface(iface, true)) {
                Log.w(TAG, "claimInterface ${iface.id} failed")
                continue
            }
            slot++
            val label = if (dongle) "Wireless receiver · slot $slot" else "USB cable"
            val link = UsbLink("usb:${device.deviceName}:${iface.id}", label, connection, iface, ep, dongle)
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

    /** A Valve HID interface: interrupt IN endpoint with 64 byte reports, not a boot keyboard/mouse. */
    private fun controllerEndpoint(iface: UsbInterface): UsbEndpoint? {
        if (iface.interfaceClass != UsbConstants.USB_CLASS_HID) return null
        if (iface.interfaceProtocol == 1 || iface.interfaceProtocol == 2) return null
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.direction == UsbConstants.USB_DIR_IN && ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.maxPacketSize == 64) return ep
        }
        return null
    }

    private class OpenDevice(val device: UsbDevice, val connection: UsbDeviceConnection) {
        val links = ArrayList<UsbLink>()
    }

    private inner class UsbLink(
        id: String,
        label: String,
        private val connection: UsbDeviceConnection,
        private val iface: UsbInterface,
        private val endpoint: UsbEndpoint,
        private val wireless: Boolean,
    ) : ControllerLink(id, label, isBle = false) {
        @Volatile var connected = false
        @Volatile private var running = false
        private var thread: Thread? = null

        override fun sendFeature(msg: ByteArray): Boolean {
            val data = SteamProtocol.usbFeature(msg)
            // HID SET_REPORT(feature, id 0) on this interface.
            val n = connection.controlTransfer(0x21, 0x09, 0x0300, iface.id, data, data.size, 250)
            return n >= 0
        }

        fun startReading() {
            running = true
            if (!wireless) markConnected()
            else sendFeature(SteamProtocol.clearDigitalMappings()) // wakes up slots that are already paired
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

        private fun readLoop() {
            val buf = ByteArray(64)
            var errors = 0
            while (running) {
                val n = connection.bulkTransfer(endpoint, buf, buf.size, 500)
                if (n <= 0) {
                    // Timeouts are normal when idle; repeated hard errors mean the device is gone.
                    if (n < 0 && ++errors > 200) break
                    continue
                }
                errors = 0
                when (SteamProtocol.parseUsb(buf, n, state)) {
                    SteamProtocol.REPORT_STATE -> {
                        markConnected()
                        listener.onControllerState(this)
                    }
                    SteamProtocol.REPORT_WIRELESS -> when (buf[4].toInt()) {
                        1 -> if (connected) {
                            connected = false
                            listener.onControllerDisconnected(this)
                        }
                        2 -> markConnected()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "UsbController"
        const val ACTION_PERMISSION = "dev.scmapper.USB_PERMISSION"

        fun isSteamController(d: UsbDevice) = d.vendorId == SteamProtocol.VENDOR_VALVE &&
            (d.productId == SteamProtocol.PRODUCT_WIRED || d.productId == SteamProtocol.PRODUCT_DONGLE)

        @Suppress("DEPRECATION")
        fun Intent.usbDevice(): UsbDevice? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }
}
