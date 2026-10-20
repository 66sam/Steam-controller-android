package dev.scmapper.output

import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs in the Shizuku helper process (shell user, which is in the "input" group): lists input
 * devices and reads raw evdev events from the ones the user picked, grabbing them so that Android
 * and games only see SC Mapper's virtual devices instead.
 */
class EvdevHost(private val onEvents: (id: Int, events: IntArray) -> Unit, private val onClosed: (id: Int) -> Unit) {
    private class Reader(val id: Int, val fd: FileDescriptor) {
        @Volatile var alive = true
    }

    private val readers = HashMap<Int, Reader>()

    /** `getevent -i` lists every input device with ids, location and capabilities (ranges included). */
    fun list(): String = try {
        val p = ProcessBuilder("/system/bin/getevent", "-i").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text
    } catch (e: Exception) {
        "error: ${e.message}"
    }

    fun open(id: Int, path: String, grab: Boolean): String? {
        close(id)
        if (!path.matches(Regex("/dev/input/event\\d+"))) return "Bad device path"
        val fd = try {
            Os.open(path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        } catch (e: Exception) {
            return "Cannot open $path: ${e.message}"
        }
        if (grab) {
            val error = grab(fd)
            if (error != null) {
                try { Os.close(fd) } catch (_: Exception) {}
                return error
            }
        }
        val r = Reader(id, fd)
        synchronized(readers) { readers[id] = r }
        Thread({ readLoop(r) }, "evdev-$id").apply { isDaemon = true; start() }
        return null
    }

    fun close(id: Int) {
        val r = synchronized(readers) { readers.remove(id) } ?: return
        r.alive = false // the reader closes the descriptor, which also releases the grab
    }

    fun closeAll() = synchronized(readers) { readers.keys.toList() }.forEach { close(it) }

    /**
     * EVIOCGRAB through the platform's ioctlInt, which passes a non-null argument pointer (the
     * kernel only checks for non-zero). Hidden API, fine in this non-app process.
     */
    private fun grab(fd: FileDescriptor): String? {
        val candidates = listOf("android.system.Os", "libcore.io.Linux")
        for (cls in candidates) {
            try {
                val c = Class.forName(cls)
                val target = if (cls == "libcore.io.Linux") Class.forName("libcore.io.Libcore").getField("os").get(null) else null
                for (m in c.methods + c.declaredMethods) {
                    if (m.name != "ioctlInt") continue
                    m.isAccessible = true
                    val args: Array<Any?> = when (m.parameterTypes.size) {
                        2 -> arrayOf(fd, EVIOCGRAB)
                        3 -> arrayOf(fd, EVIOCGRAB, m.parameterTypes[2].getConstructor(Int::class.javaPrimitiveType).newInstance(1))
                        else -> continue
                    }
                    m.invoke(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else target, *args)
                    return null
                }
            } catch (e: Exception) {
                Log.w(TAG, "grab via $cls failed: ${e.cause?.message ?: e.message}")
            }
        }
        return "Could not take exclusive control of the device (EVIOCGRAB)"
    }

    private fun readLoop(r: Reader) {
        val size = if (Process.is64Bit()) 24 else 16
        val buf = ByteArray(size * 64)
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val pending = ArrayList<Int>()
        val pfd = StructPollfd().apply { fd = r.fd; events = OsConstants.POLLIN.toShort() }
        try {
            while (r.alive) {
                if (Os.poll(arrayOf(pfd), 250) <= 0) continue
                val n = Os.read(r.fd, buf, 0, buf.size)
                if (n <= 0) break
                var o = 0
                while (o + size <= n) {
                    val type = bb.getShort(o + size - 8).toInt() and 0xFFFF
                    val code = bb.getShort(o + size - 6).toInt() and 0xFFFF
                    val value = bb.getInt(o + size - 4)
                    o += size
                    if (type == EV_SYN) {
                        if (code == SYN_REPORT && pending.isNotEmpty()) {
                            onEvents(r.id, pending.toIntArray())
                            pending.clear()
                        } else if (code == SYN_DROPPED) {
                            pending.clear()
                        }
                        continue
                    }
                    if (type == EV_MSC) continue
                    pending += type; pending += code; pending += value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "evdev ${r.id} stopped: ${e.message}")
        } finally {
            try { Os.close(r.fd) } catch (_: Exception) {}
            val unexpected = synchronized(readers) { readers[r.id] === r }
            if (unexpected) {
                synchronized(readers) { readers.remove(r.id) }
                onClosed(r.id)
            }
        }
    }

    companion object {
        private const val TAG = "EvdevHost"
        /** _IOW('E', 0x90, int) */
        private const val EVIOCGRAB = 0x40044590
        private const val EV_SYN = 0
        private const val EV_MSC = 4
        private const val SYN_REPORT = 0
        private const val SYN_DROPPED = 3
    }
}
