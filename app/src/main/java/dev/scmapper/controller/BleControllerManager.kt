package dev.scmapper.controller

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque
import java.util.UUID

/**
 * Connects to a Steam Controller running the BLE firmware.
 *
 * The controller must first be paired in Android's Bluetooth settings (hold Steam + Y while
 * powering on). It then shows up as a HID keyboard/mouse ("lizard mode"); this class opens a
 * GATT connection to the Valve service, which switches it into full controller mode.
 */
@SuppressLint("MissingPermission")
class BleControllerManager(private val context: Context, private val listener: ControllerListener) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val links = HashMap<String, BleLink>()
    private val main = Handler(Looper.getMainLooper())

    /** Bonded devices that look like Steam Controllers. */
    fun bondedControllers(): List<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty().filter { looksLikeSteamController(it) }
    } catch (_: SecurityException) {
        emptyList()
    }

    fun allBonded(): List<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty().toList()
    } catch (_: SecurityException) {
        emptyList()
    }

    @Synchronized
    fun connect(address: String) {
        if (links.containsKey(address)) return
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null } ?: return
        val link = BleLink(device)
        links[address] = link
        link.connect()
    }

    @Synchronized
    fun disconnect(address: String) {
        links.remove(address)?.close()
    }

    fun stop() {
        synchronized(this) { links.values.toList() }.forEach { it.close() }
        synchronized(this) { links.clear() }
    }

    fun isConnecting(address: String) = synchronized(this) { links.containsKey(address) }

    private inner class BleLink(private val device: BluetoothDevice) :
        ControllerLink("ble:${device.address}", "Bluetooth · ${safeName(device)}", isBle = true) {

        private var gatt: BluetoothGatt? = null
        private var report: BluetoothGattCharacteristic? = null
        private var battery: BluetoothGattCharacteristic? = null
        @Volatile private var ready = false
        @Volatile private var closed = false
        private val ops = ArrayDeque<() -> Boolean>()
        private var opInFlight = false
        private val batteryPoll = object : Runnable {
            override fun run() {
                val b = battery
                val g = gatt
                if (closed || !ready) return
                if (b != null && g != null) enqueue { g.readCharacteristic(b) }
                main.postDelayed(this, 60_000)
            }
        }

        override val busy: Boolean get() = synchronized(ops) { ops.size > 2 }

        fun connect() {
            gatt = if (Build.VERSION.SDK_INT >= 26) device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, true, callback)
        }

        fun close() {
            closed = true
            main.removeCallbacks(batteryPoll)
            if (ready) {
                ready = false
                listener.onControllerDisconnected(this)
            }
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        }

        override fun sendFeature(msg: ByteArray): Boolean {
            val c = report ?: return false
            val g = gatt ?: return false
            for (segment in SteamProtocol.bleSegments(msg)) enqueue { write(g, c, segment) }
            return true
        }

        @Suppress("DEPRECATION")
        private fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean =
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                c.value = value
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                g.writeCharacteristic(c)
            }

        private fun enqueue(op: () -> Boolean) {
            synchronized(ops) {
                // Never let haptics back up the queue; drop the oldest instead.
                if (ops.size > 16) ops.pollFirst()
                ops.addLast(op)
            }
            pump()
        }

        private fun pump() {
            while (true) {
                val op = synchronized(ops) {
                    if (opInFlight) return
                    val next = ops.pollFirst() ?: return
                    opInFlight = true
                    next
                }
                val started = try { op() } catch (e: SecurityException) { false }
                if (started) return
                synchronized(ops) { opInFlight = false }
            }
        }

        private fun opDone() {
            synchronized(ops) { opInFlight = false }
            pump()
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (closed) return
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    main.postDelayed({ if (!closed) g.discoverServices() }, 300)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(ops) { ops.clear(); opInFlight = false }
                    report = null
                    if (ready) {
                        ready = false
                        listener.onControllerDisconnected(this@BleLink)
                    }
                    // autoConnect=true keeps the link armed; the controller reconnects when switched on.
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val service = g.getService(UUID.fromString(SteamProtocol.BLE_SERVICE))
                if (service == null) {
                    Log.w(TAG, "Valve service not found on ${device.address}; is the BLE firmware installed?")
                    return
                }
                val input = service.getCharacteristic(UUID.fromString(SteamProtocol.BLE_INPUT_CHAR))
                report = service.getCharacteristic(UUID.fromString(SteamProtocol.BLE_REPORT_CHAR))
                battery = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
                if (input == null || report == null) return
                g.setCharacteristicNotification(input, true)
                val cccd = input.getDescriptor(CCCD)
                if (cccd != null) enqueue { writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                enqueue {
                    // Everything queued so far has gone out: the controller is ready.
                    ready = true
                    listener.onControllerConnected(this@BleLink)
                    main.post(batteryPoll)
                    false
                }
            }

            @Suppress("DEPRECATION")
            private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray): Boolean =
                if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == 0
                else {
                    d.value = value
                    g.writeDescriptor(d)
                }

            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = opDone()

            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = opDone()

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                onRead(c, c.value)
            }

            override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                onRead(c, value)
            }

            private fun onRead(c: BluetoothGattCharacteristic, value: ByteArray?) {
                if (c.uuid == BATTERY_LEVEL && value != null && value.isNotEmpty()) state.battery = value[0].toInt() and 0xFF
                opDone()
            }

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                onNotify(c.value)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onNotify(value)
            }

            private fun onNotify(value: ByteArray?) {
                if (value == null || !ready) return
                if (SteamProtocol.parseBle(value, state)) listener.onControllerState(this@BleLink)
            }
        }
    }

    companion object {
        private const val TAG = "BleController"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: SecurityException) { d.address }

        fun looksLikeSteamController(d: BluetoothDevice): Boolean =
            safeName(d).replace(" ", "").contains("steamcontroller", ignoreCase = true)
    }
}
