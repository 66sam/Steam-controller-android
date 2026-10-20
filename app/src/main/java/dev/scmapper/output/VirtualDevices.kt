package dev.scmapper.output

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dev.scmapper.BuildConfig
import rikka.shizuku.Shizuku

/**
 * App-side handle on [UhidService]. Binds the service through Shizuku and forwards HID reports.
 */
class VirtualDevices(private val context: Context, private val onOutput: (id: Int, report: ByteArray) -> Unit) {
    enum class Status { SHIZUKU_MISSING, NO_PERMISSION, CONNECTING, READY, ERROR }

    @Volatile var status = Status.CONNECTING
        private set
    @Volatile var lastError: String? = null
        private set
    var onReady: (() -> Unit)? = null
    /** Raw input batches (type, code, value triples) from devices opened with [openEvdev]. */
    var onEvdev: ((id: Int, events: IntArray) -> Unit)? = null
    var onEvdevClosed: ((id: Int) -> Unit)? = null

    @Volatile private var remote: IBinder? = null
    private val created = HashSet<Int>()

    private val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, UhidService::class.java.name))
        .daemon(false)
        .processNameSuffix("uhid")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                UhidProtocol.CB_OUTPUT -> {
                    data.enforceInterface(UhidProtocol.CALLBACK_DESCRIPTOR)
                    val id = data.readInt()
                    val report = data.createByteArray() ?: return true
                    onOutput(id, report)
                }
                UhidProtocol.CB_EVDEV -> {
                    data.enforceInterface(UhidProtocol.CALLBACK_DESCRIPTOR)
                    val id = data.readInt()
                    val events = data.createIntArray() ?: return true
                    onEvdev?.invoke(id, events)
                }
                UhidProtocol.CB_EVDEV_CLOSED -> {
                    data.enforceInterface(UhidProtocol.CALLBACK_DESCRIPTOR)
                    onEvdevClosed?.invoke(data.readInt())
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                fail("Helper service did not start")
                return
            }
            remote = binder
            val ok = call(UhidProtocol.TX_SET_CALLBACK) { it.writeStrongBinder(callback) } != null
            if (ok) {
                status = Status.READY
                lastError = null
                onReady?.invoke()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            synchronized(created) { created.clear() }
            status = Status.CONNECTING
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { bind() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        remote = null
        status = Status.SHIZUKU_MISSING
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) bind() else status = Status.NO_PERMISSION
    }

    fun start() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        if (!Shizuku.pingBinder()) status = Status.SHIZUKU_MISSING
    }

    fun stop() {
        destroyAll()
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        try {
            if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, true)
        } catch (e: Exception) {
            Log.w(TAG, "unbind failed", e)
        }
        remote = null
    }

    /** (Re)binds the helper; asks for Shizuku permission if needed. */
    fun bind() {
        if (!Shizuku.pingBinder()) {
            status = Status.SHIZUKU_MISSING
            return
        }
        if (Shizuku.isPreV11()) {
            fail("Shizuku is too old, please update it")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            status = Status.NO_PERMISSION
            return
        }
        if (remote?.pingBinder() == true) return
        status = Status.CONNECTING
        try {
            Shizuku.bindUserService(args, connection)
        } catch (e: Exception) {
            fail("bindUserService: ${e.message}")
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1)
        else bind()
    }

    val isReady get() = status == Status.READY && remote != null

    fun isCreated(id: Int) = synchronized(created) { id in created }

    fun create(id: Int, name: String, vendor: Int, product: Int, bus: Int, descriptor: ByteArray): Boolean {
        val reply = call(UhidProtocol.TX_CREATE) {
            it.writeInt(id); it.writeString(name); it.writeInt(vendor); it.writeInt(product); it.writeInt(bus)
            it.writeByteArray(descriptor)
        } ?: return false
        if (reply.isNotEmpty()) {
            fail(reply)
            return false
        }
        synchronized(created) { created += id }
        return true
    }

    fun destroy(id: Int) {
        synchronized(created) { if (!created.remove(id)) return }
        call(UhidProtocol.TX_DESTROY) { it.writeInt(id) }
    }

    fun destroyAll() {
        synchronized(created) { created.toList() }.forEach { destroy(it) }
    }

    fun send(id: Int, report: ByteArray) {
        val binder = remote ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(UhidProtocol.DESCRIPTOR)
            data.writeInt(id)
            data.writeByteArray(report)
            binder.transact(UhidProtocol.TX_INPUT, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        } finally {
            data.recycle()
        }
    }

    /** `getevent -i` output from the helper, or null if the helper isn't running. */
    fun listInputDevices(): String? = call(UhidProtocol.TX_LIST_EVDEV) {}

    /** Starts reading (and optionally grabbing) an input device; returns an error message or null. */
    fun openEvdev(id: Int, path: String, grab: Boolean): String? {
        val r = call(UhidProtocol.TX_OPEN_EVDEV) { it.writeInt(id); it.writeString(path); it.writeInt(if (grab) 1 else 0) }
            ?: return lastError ?: "Helper not running"
        return r.ifEmpty { null }
    }

    fun closeEvdev(id: Int) {
        call(UhidProtocol.TX_CLOSE_EVDEV) { it.writeInt(id) }
    }

    /** Synchronous call; returns the optional string result ("" for none), or null on failure. */
    private fun call(code: Int, write: (Parcel) -> Unit): String? {
        val binder = remote ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(UhidProtocol.DESCRIPTOR)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            if (code == UhidProtocol.TX_CREATE || code == UhidProtocol.TX_LIST_EVDEV || code == UhidProtocol.TX_OPEN_EVDEV) reply.readString() ?: "" else ""
        } catch (e: Exception) {
            fail("Helper call failed: ${e.message}")
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        lastError = message
        status = Status.ERROR
    }

    companion object {
        private const val TAG = "VirtualDevices"
    }
}
