package dev.scmapper.output

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * Runs inside a process started by Shizuku with shell (or root) privileges, where /dev/uhid is
 * accessible. Each virtual device is one open file descriptor on /dev/uhid; the kernel turns it
 * into a regular input device, so Android and games see real hardware. It also reads other
 * controllers' raw input through [EvdevHost].
 *
 * Talks to the app through a hand-written Binder protocol (see [UhidProtocol]).
 */
class UhidService : Binder() {
    private class Device(val id: Int, val fd: FileDescriptor) {
        @Volatile var alive = true
    }

    private val devices = HashMap<Int, Device>()
    @Volatile private var callback: IBinder? = null
    private val evdev = EvdevHost(
        onEvents = { id, events -> callback(UhidProtocol.CB_EVDEV) { it.writeInt(id); it.writeIntArray(events) } },
        onClosed = { id -> callback(UhidProtocol.CB_EVDEV_CLOSED) { it.writeInt(id) } },
    )

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            UhidProtocol.TX_SET_CALLBACK -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                callback = data.readStrongBinder()
                reply?.writeNoException()
            }
            UhidProtocol.TX_CREATE -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                val id = data.readInt()
                val name = data.readString() ?: "Virtual device"
                val vendor = data.readInt()
                val product = data.readInt()
                val bus = data.readInt()
                val descriptor = data.createByteArray() ?: ByteArray(0)
                val error = create(id, name, vendor, product, bus, descriptor)
                reply?.writeNoException()
                reply?.writeString(error)
            }
            UhidProtocol.TX_DESTROY -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                destroy(data.readInt())
                reply?.writeNoException()
            }
            UhidProtocol.TX_INPUT -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                val id = data.readInt()
                val report = data.createByteArray() ?: return true
                input(id, report)
            }
            UhidProtocol.TX_LIST_EVDEV -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                val text = evdev.list()
                reply?.writeNoException()
                reply?.writeString(text)
            }
            UhidProtocol.TX_OPEN_EVDEV -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                val id = data.readInt()
                val path = data.readString() ?: ""
                val grab = data.readInt() != 0
                val error = evdev.open(id, path, grab)
                reply?.writeNoException()
                reply?.writeString(error)
            }
            UhidProtocol.TX_CLOSE_EVDEV -> {
                data.enforceInterface(UhidProtocol.DESCRIPTOR)
                evdev.close(data.readInt())
                reply?.writeNoException()
            }
            UhidProtocol.TX_SHIZUKU_DESTROY -> {
                evdev.closeAll()
                synchronized(devices) { devices.keys.toList() }.forEach { destroy(it) }
                exitProcess(0)
            }
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    private fun create(id: Int, name: String, vendor: Int, product: Int, bus: Int, descriptor: ByteArray): String? {
        destroy(id)
        val fd = try {
            Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        } catch (e: Exception) {
            return "Cannot open /dev/uhid: ${e.message}"
        }
        val ev = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        ev.putInt(0, UHID_CREATE2)
        val nameBytes = name.toByteArray().copyOf(127)
        ev.position(4); ev.put(nameBytes)
        val phys = "scmapper/$id".toByteArray()
        ev.position(132); ev.put(phys)
        ev.putShort(260, descriptor.size.toShort())
        ev.putShort(262, bus.toShort())
        ev.putInt(264, vendor)
        ev.putInt(268, product)
        ev.putInt(272, 0x0100)
        ev.putInt(276, 0)
        ev.position(280); ev.put(descriptor)
        try {
            Os.write(fd, ev.array(), 0, EVENT_SIZE)
        } catch (e: Exception) {
            try { Os.close(fd) } catch (_: Exception) {}
            return "UHID_CREATE2 failed: ${e.message}"
        }
        val device = Device(id, fd)
        synchronized(devices) { devices[id] = device }
        Thread({ readLoop(device) }, "uhid-$id").apply { isDaemon = true; start() }
        return null
    }

    private fun destroy(id: Int) {
        val device = synchronized(devices) { devices.remove(id) } ?: return
        device.alive = false
        try {
            val ev = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, UHID_DESTROY)
            Os.write(device.fd, ev.array(), 0, 4)
        } catch (_: Exception) {
        }
        // The reader thread closes the descriptor once it notices.
    }

    private fun input(id: Int, report: ByteArray) {
        val device = synchronized(devices) { devices[id] } ?: return
        val size = 6 + report.size
        val ev = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        ev.putInt(0, UHID_INPUT2)
        ev.putShort(4, report.size.toShort())
        ev.position(6); ev.put(report)
        try {
            Os.write(device.fd, ev.array(), 0, size)
        } catch (e: Exception) {
            Log.w(TAG, "input write failed: ${e.message}")
        }
    }

    private fun readLoop(device: Device) {
        val buf = ByteArray(EVENT_SIZE + 4)
        val pfd = StructPollfd().apply { fd = device.fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (device.alive) {
                if (Os.poll(arrayOf(pfd), 250) <= 0) continue
                val n = Os.read(device.fd, buf, 0, buf.size)
                if (n < 4) continue
                val ev = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                when (ev.getInt(0)) {
                    UHID_OUTPUT -> {
                        val size = ev.getShort(4100).toInt() and 0xFFFF
                        sendOutput(device.id, buf.copyOfRange(4, 4 + size.coerceAtMost(4096)))
                    }
                    UHID_GET_REPORT -> {
                        val reply = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                        reply.putInt(0, UHID_GET_REPORT_REPLY).putInt(4, ev.getInt(4)).putShort(8, EIO).putShort(10, 0)
                        Os.write(device.fd, reply.array(), 0, 12)
                    }
                    UHID_SET_REPORT -> {
                        val reply = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                        reply.putInt(0, UHID_SET_REPORT_REPLY).putInt(4, ev.getInt(4)).putShort(8, 0)
                        Os.write(device.fd, reply.array(), 0, 10)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "uhid reader ${device.id} stopped: ${e.message}")
        } finally {
            try { Os.close(device.fd) } catch (_: Exception) {}
        }
    }

    private fun sendOutput(id: Int, report: ByteArray) = callback(UhidProtocol.CB_OUTPUT) {
        it.writeInt(id)
        it.writeByteArray(report)
    }

    private fun callback(code: Int, write: (Parcel) -> Unit) {
        val cb = callback ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(UhidProtocol.CALLBACK_DESCRIPTOR)
            write(data)
            cb.transact(code, data, null, FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "callback failed: ${e.message}")
        } finally {
            data.recycle()
        }
    }

    companion object {
        private const val TAG = "UhidService"
        // sizeof(struct uhid_event): 4 byte type + the largest member (uhid_create2_req).
        private const val EVENT_SIZE = 4376
        private const val UHID_DESTROY = 1
        private const val UHID_OUTPUT = 6
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14
        private const val EIO: Short = 5
    }
}

object UhidProtocol {
    const val DESCRIPTOR = "dev.scmapper.IUhid"
    const val CALLBACK_DESCRIPTOR = "dev.scmapper.IUhidCallback"
    const val TX_SET_CALLBACK = IBinder.FIRST_CALL_TRANSACTION
    const val TX_CREATE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TX_DESTROY = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TX_INPUT = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TX_LIST_EVDEV = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TX_OPEN_EVDEV = IBinder.FIRST_CALL_TRANSACTION + 5
    const val TX_CLOSE_EVDEV = IBinder.FIRST_CALL_TRANSACTION + 6
    /** Transaction Shizuku sends to tell a user service to exit. */
    const val TX_SHIZUKU_DESTROY = 16777115
    const val CB_OUTPUT = IBinder.FIRST_CALL_TRANSACTION
    const val CB_EVDEV = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CB_EVDEV_CLOSED = IBinder.FIRST_CALL_TRANSACTION + 2
}
