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
import dev.scmapper.DiagLog
import java.util.ArrayDeque
import java.util.UUID

/**
 * Connects to Steam Controllers over Bluetooth LE: the 2015 controller with Valve's BLE firmware,
 * and the 2026 controller.
 *
 * The controller is first paired in Android's Bluetooth settings, where it shows up as a HID
 * keyboard/mouse ("lizard mode"). This class opens a GATT connection to the Valve service, which
 * gives access to the full controller.
 */
@SuppressLint("MissingPermission")
class BleControllerManager(private val context: Context, private val listener: ControllerListener) {
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val connections = HashMap<String, BleConnection>()
    private val main = Handler(Looper.getMainLooper())

    /** Bonded devices that look like Steam Controllers. */
    fun bondedControllers(): List<BluetoothDevice> = allBonded().filter { looksLikeSteamController(it) }

    fun allBonded(): List<BluetoothDevice> = try {
        adapter?.bondedDevices.orEmpty().toList()
    } catch (_: SecurityException) {
        emptyList()
    }

    @Synchronized
    fun connect(address: String) {
        if (connections.containsKey(address)) return
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null } ?: return
        val c = BleConnection(device)
        connections[address] = c
        c.connect()
    }

    @Synchronized
    fun disconnect(address: String) {
        connections.remove(address)?.close()
    }

    fun stop() {
        synchronized(this) { connections.values.toList() }.forEach { it.close() }
        synchronized(this) { connections.clear() }
    }

    /** One GATT connection; creates a [BleLink] once the controller model is known. */
    private inner class BleConnection(private val device: BluetoothDevice) {
        private var gatt: BluetoothGatt? = null
        @Volatile private var link: BleLink? = null
        @Volatile private var closed = false
        private var featureChar: BluetoothGattCharacteristic? = null
        private var inputChar: BluetoothGattCharacteristic? = null
        private var batteryChar: BluetoothGattCharacteristic? = null
        private val outputChars = HashMap<Int, BluetoothGattCharacteristic>()
        private val assembler = Sc2015Protocol.BleAssembler()
        private var model = ControllerModel.SC2015
        /** 2026 notifications don't carry the report id; this is prepended before parsing. */
        private var inputReportId = 0
        private val ops = ArrayDeque<() -> Boolean>()
        private var opInFlight = false
        private val batteryPoll = object : Runnable {
            override fun run() {
                val b = batteryChar
                val g = gatt
                if (closed || link == null) return
                if (b != null && g != null) enqueue { g.readCharacteristic(b) }
                main.postDelayed(this, 60_000)
            }
        }

        fun connect() {
            DiagLog.log("BLE ${safeName(device)}: connecting")
            gatt = if (Build.VERSION.SDK_INT >= 26) device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, true, callback)
        }

        fun close() {
            closed = true
            main.removeCallbacks(batteryPoll)
            dropLink()
            gatt?.disconnect()
            gatt?.close()
            gatt = null
        }

        private fun dropLink() {
            val l = link ?: return
            link = null
            listener.onControllerDisconnected(l)
        }

        val busy: Boolean get() = synchronized(ops) { ops.size > 2 }

        fun writeFeature(msg: ByteArray): Boolean {
            val c = featureChar ?: return false
            val g = gatt ?: return false
            if (model == ControllerModel.SC2026) {
                // Unsegmented, padded like SDL does (62 bytes); Android uses a long write if needed.
                val value = msg.copyOf(62)
                enqueue { write(g, c, value) }
            } else {
                for (segment in Sc2015Protocol.bleSegments(msg)) enqueue { write(g, c, segment) }
            }
            return true
        }

        fun writeOutput(report: ByteArray): Boolean {
            val g = gatt ?: return false
            val c = outputChars[report[0].toInt() and 0xFF] ?: return false
            val value = report.copyOfRange(1, report.size)
            enqueue { write(g, c, value) }
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

        private fun findCharacteristics(g: BluetoothGatt): Boolean {
            val service = g.getService(UUID.fromString(Sc2015Protocol.BLE_SERVICE)) ?: return false
            outputChars.clear()
            var d0gInput: BluetoothGattCharacteristic? = null
            var tritonInput: BluetoothGattCharacteristic? = null
            for (c in service.characteristics) {
                when (c.uuid.toString().lowercase()) {
                    Sc2015Protocol.BLE_INPUT_CHAR -> d0gInput = c
                    Sc2015Protocol.BLE_REPORT_CHAR -> featureChar = c
                    Sc2026Protocol.BLE_INPUT_CHAR_45 -> if (tritonInput == null) { tritonInput = c; inputReportId = Sc2026Protocol.ID_STATE_BLE }
                    Sc2026Protocol.BLE_INPUT_CHAR_47 -> if (tritonInput == null) { tritonInput = c; inputReportId = Sc2026Protocol.ID_STATE_TIMESTAMP }
                }
            }
            if (tritonInput != null) {
                model = ControllerModel.SC2026
                inputChar = tritonInput
                for (id in listOf(Sc2026Protocol.ID_OUT_RUMBLE, Sc2026Protocol.ID_OUT_HAPTIC_PULSE)) {
                    service.getCharacteristic(UUID.fromString(Sc2026Protocol.bleOutputCharFor(id)))?.let { outputChars[id] = it }
                }
            } else {
                model = ControllerModel.SC2015
                inputChar = d0gInput
            }
            batteryChar = g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)
            return inputChar != null && featureChar != null
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (closed) return
                DiagLog.log("BLE ${safeName(device)}: state $newState (status $status)")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    main.postDelayed({ if (!closed) g.discoverServices() }, 300)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    synchronized(ops) { ops.clear(); opInFlight = false }
                    dropLink()
                    // autoConnect=true keeps the link armed; the controller reconnects when switched on.
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                DiagLog.log(
                    "BLE ${safeName(device)}: services " + g.services.joinToString { svc ->
                        svc.uuid.toString().substring(4, 8) + "[" + svc.characteristics.joinToString(" ") { it.uuid.toString().substring(4, 8) } + "]"
                    }
                )
                if (!findCharacteristics(g)) {
                    DiagLog.log("BLE ${safeName(device)}: Valve service not found (2015 controller: is the BLE firmware installed?)")
                    return
                }
                DiagLog.log("BLE ${safeName(device)}: ${model.title}")
                val input = inputChar ?: return
                // Larger MTU for the 2026 controller's long reports.
                enqueue { g.requestMtu(247) }
                g.setCharacteristicNotification(input, true)
                val cccd = input.getDescriptor(CCCD)
                if (cccd != null) enqueue { writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                enqueue {
                    // Everything queued so far has gone out: the controller is ready.
                    val l = BleLink(this@BleConnection, device, model)
                    link = l
                    listener.onControllerConnected(l)
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

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = opDone()

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
                if (c.uuid == BATTERY_LEVEL && value != null && value.isNotEmpty()) link?.state?.battery = value[0].toInt() and 0xFF
                opDone()
            }

            @Deprecated("Deprecated in API 33")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                onNotify(c, c.value)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                onNotify(c, value)
            }

            private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray?) {
                val l = link ?: return
                if (value == null || c.uuid != inputChar?.uuid) return
                DiagLog.once("ble:${device.address}:first") { "BLE ${safeName(device)}: first notification ${DiagLog.hex(value)}" }
                val ok = if (model == ControllerModel.SC2026) {
                    val report = ByteArray(value.size + 1)
                    report[0] = inputReportId.toByte()
                    System.arraycopy(value, 0, report, 1, value.size)
                    Sc2026Protocol.parse(report, report.size, l.state) == Sc2026Protocol.Result.STATE
                } else {
                    // 2015 reports arrive in segments; null means "wait for the rest".
                    val report = assembler.add(value) ?: return
                    DiagLog.once("ble:${device.address}:report") { "BLE ${safeName(device)}: first report ${DiagLog.hex(report)}" }
                    Sc2015Protocol.parseBle(report, l.state)
                }
                if (ok) listener.onControllerState(l)
                else DiagLog.once("ble:${device.address}:bad:${value.firstOrNull()}") { "BLE ${safeName(device)}: unrecognised notification ${DiagLog.hex(value)}" }
            }
        }
    }

    private class BleLink(private val conn: BleConnection, device: BluetoothDevice, model: ControllerModel) :
        ControllerLink("ble:${device.address}", "${model.title} · Bluetooth", model, isBle = true) {
        override val busy: Boolean get() = conn.busy
        override fun sendFeature(msg: ByteArray) = conn.writeFeature(msg)
        override fun sendOutput(report: ByteArray) = conn.writeOutput(report)
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        fun safeName(d: BluetoothDevice): String = try { d.name ?: d.address } catch (_: SecurityException) { d.address }

        /** 2015 controllers advertise as "SteamController", 2026 ones as "Steam Ctrl…". */
        fun looksLikeSteamController(d: BluetoothDevice): Boolean {
            val name = safeName(d)
            return name.replace(" ", "").contains("steamcontroller", ignoreCase = true) ||
                name.startsWith("Steam Ctrl", ignoreCase = true)
        }
    }
}
