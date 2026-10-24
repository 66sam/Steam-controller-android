package dev.scmapper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import dev.scmapper.App
import dev.scmapper.DiagLog
import dev.scmapper.R
import dev.scmapper.controller.BleControllerManager
import dev.scmapper.controller.ControllerLink
import dev.scmapper.controller.ControllerListener
import dev.scmapper.controller.ControllerModel
import dev.scmapper.controller.GenericControllerManager
import dev.scmapper.controller.RawState
import dev.scmapper.controller.UsbControllerManager
import dev.scmapper.mapping.GyroMode
import dev.scmapper.mapping.Mapper
import dev.scmapper.mapping.MapperHost
import dev.scmapper.mapping.MenuNav
import dev.scmapper.mapping.Profile
import dev.scmapper.mapping.Special
import dev.scmapper.output.OutputHub
import dev.scmapper.output.VirtualDevices
import dev.scmapper.ui.MainActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that ties everything together:
 * controllers (USB/BLE) -> [Mapper] per controller on the engine thread -> [OutputHub] -> virtual devices.
 */
class BridgeService : Service(), ControllerListener {
    private val app get() = application as App
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engineThread: HandlerThread
    private lateinit var engine: Handler

    lateinit var devices: VirtualDevices
        private set
    private lateinit var hub: OutputHub
    private lateinit var usb: UsbControllerManager
    lateinit var ble: BleControllerManager
        private set
    private lateinit var menu: OverlayMenu
    lateinit var generic: GenericControllerManager
        private set
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = generic.refresh()
        override fun onInputDeviceRemoved(deviceId: Int) = generic.refresh()
        override fun onInputDeviceChanged(deviceId: Int) {}
    }

    @Volatile private var menuOpen = false
    @Volatile var gyroEnabled = true
        private set
    private lateinit var activeProfile: Profile

    private val entries = ConcurrentHashMap<String, Entry>()
    private var ticking = false
    private var failed = false

    /** One connected controller. */
    inner class Entry(val link: ControllerLink) : MapperHost {
        var slot = -1
        val mapper = Mapper(this)
        val pending = RawState()
        private val work = RawState()
        val scheduled = AtomicBoolean(false)
        var lastStepNanos = 0L

        fun step() {
            synchronized(pending) { work.copyFrom(pending) }
            val nanos = System.nanoTime()
            lastStepNanos = nanos
            mapper.process(work, SystemClock.uptimeMillis(), nanos)
            if (work.buttons != 0) DiagLog.once("input:${link.id}") { "${link.label}: input received (${work.describe().lineSequence().first()})" }
            if (!menuOpen) hub.submit(slot, mapper.frame)
        }

        override fun haptic(right: Boolean, onUs: Int, offUs: Int, count: Int) {
            if (!link.busy) link.haptic(right, onUs, offUs, count)
        }

        override fun rumble(low: Float, high: Float) = link.rumble(low, high)

        override fun special(s: Special) = onSpecial(this, s)

        override fun turnOffController() {
            link.turnOff()
        }

        override fun calibrated() {
            mapper.confirm(2)
            toast("Gyro calibrated")
        }

        override val menuOpen: Boolean get() = this@BridgeService.menuOpen

        override fun menuNavigate(nav: MenuNav) {
            main.post {
                when (nav) {
                    MenuNav.UP -> menu.up()
                    MenuNav.DOWN -> menu.down()
                    MenuNav.SELECT -> menu.select()
                    MenuNav.CLOSE -> menu.close()
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) {
            failed = true
            stopSelf()
            return
        }
        lastStartError = null
        DiagLog.log("Service started (SC Mapper ${dev.scmapper.BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE})")
        instance = this
        engineThread = HandlerThread("engine", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        engine = Handler(engineThread.looper)
        activeProfile = app.profiles.active()

        devices = VirtualDevices(this) { id, report -> engine.post { onDeviceOutput(id, report) } }
        hub = OutputHub(devices)
        devices.onReady = {
            engine.post {
                hub.setIdentity(app.settings.identity)
                hub.recreateAll()
                applyProfile()
            }
            generic.refresh()
        }
        generic = GenericControllerManager(devices, app.settings, this)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputDeviceListener, main)
        devices.start()

        menu = OverlayMenu(this, onSelect = { name -> app.profiles.activeName = name }, onClosed = { menuOpen = false })
        app.profiles.addListener(profileListener)

        usb = UsbControllerManager(this, this)
        usb.start()
        ble = BleControllerManager(this, this)
        app.settings.bleAddresses.forEach { ble.connect(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESCAN -> usb.scan()
        }
        devices.bind()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        if (failed) {
            super.onDestroy()
            return
        }
        app.profiles.removeListener(profileListener)
        main.post { if (menu.isShowing) menu.close() }
        usb.stop()
        ble.stop()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputDeviceListener)
        generic.stop()
        val done = Object()
        engine.post {
            hub.releaseAll()
            devices.stop()
            synchronized(done) { done.notifyAll() }
        }
        synchronized(done) { done.wait(500) }
        engineThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Returns false if Android refused (e.g. the Bluetooth permission was denied on Android 14+). */
    private fun startInForeground(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Controller bridge", NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification("Starting…")
        return try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(NOTIFICATION_ID, n)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start in foreground", e)
            lastStartError = e.message ?: e.toString()
            DiagLog.log("Service could not start: $lastStartError")
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SC Mapper")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun updateNotification() {
        val n = entries.size
        val text = (if (n == 0) "No controller connected" else "$n controller${if (n > 1) "s" else ""} connected") +
            " · " + activeProfile.name
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ------------------------------------------------------------------ controllers

    override fun onControllerConnected(link: ControllerLink) {
        val e = Entry(link)
        entries[link.id] = e
        engine.post {
            e.slot = (0..15).first { s -> entries.values.none { it !== e && it.slot == s } }
            DiagLog.log("${link.label}: player ${e.slot + 1}, profile \"${activeProfile.name}\"")
            DiagLog.forget("input:${link.id}")
            e.mapper.profile = activeProfile.copy()
            e.mapper.gyroEnabled = gyroEnabled
            hub.ensureGamepad(e.slot)
            configure(e)
            e.mapper.confirm(1)
            startTicking()
            main.post { updateNotification() }
        }
    }

    override fun onControllerState(link: ControllerLink) {
        val e = entries[link.id] ?: return
        synchronized(e.pending) { e.pending.copyFrom(link.state) }
        if (!e.scheduled.getAndSet(true)) engine.post {
            e.scheduled.set(false)
            if (entries[link.id] === e && e.slot >= 0) e.step()
        }
    }

    override fun onControllerDisconnected(link: ControllerLink) {
        val e = entries.remove(link.id) ?: return
        engine.post {
            if (e.slot >= 0) hub.removeGamepad(e.slot)
            main.post { updateNotification() }
        }
    }

    private fun configure(e: Entry) {
        e.link.configure(gyro = activeProfile.gyro.mode != GyroMode.OFF, ledBrightness = activeProfile.ledBrightness)
    }

    /** Steps every controller at least every [TICK_MS] so time-based behaviour runs without input. */
    private fun startTicking() {
        if (ticking) return
        ticking = true
        engine.post(object : Runnable {
            override fun run() {
                if (entries.isEmpty()) {
                    ticking = false
                    return
                }
                val now = System.nanoTime()
                val nowMs = SystemClock.uptimeMillis()
                for (e in entries.values) {
                    if (e.slot < 0) continue
                    if (now - e.lastStepNanos > (TICK_MS - 2) * 1_000_000) e.step()
                    e.link.tick(nowMs)
                }
                engine.postDelayed(this, TICK_MS)
            }
        })
    }

    private fun onDeviceOutput(id: Int, report: ByteArray) {
        val slot = hub.slotOf(id) ?: return
        // hid-microsoft rumble report: id 3, enable, lt, rt, strong, weak (0..100), ...
        if (report.size >= 6 && report[0].toInt() == 3) {
            val strong = (report[4].toInt() and 0xFF) / 100f
            val weak = (report[5].toInt() and 0xFF) / 100f
            entries.values.firstOrNull { it.slot == slot }?.mapper?.setRumble(strong, weak, SystemClock.uptimeMillis())
        }
    }

    // ------------------------------------------------------------------ profiles and shortcuts

    private val profileListener: () -> Unit = { engine.post { applyProfile() } }

    private fun applyProfile() {
        val p = app.profiles.active()
        val changedName = p.name != activeProfile.name
        activeProfile = p
        hub.setSharedDevices(p.usesKeyboard(), p.usesMouse())
        for (e in entries.values) {
            e.mapper.profile = p.copy()
            configure(e)
        }
        main.post { updateNotification() }
        if (changedName) DiagLog.log("Active profile: ${p.name}")
    }

    private fun onSpecial(e: Entry, s: Special) {
        DiagLog.log("Shortcut: ${s.label}")
        when (s) {
            Special.NEXT_PROFILE, Special.PREV_PROFILE -> {
                val names = app.profiles.names()
                if (names.isEmpty()) return
                val i = names.indexOf(app.profiles.activeName)
                val next = names[((if (i < 0) 0 else i) + (if (s == Special.NEXT_PROFILE) 1 else -1) + names.size) % names.size]
                app.profiles.activeName = next
                e.mapper.confirm(1)
                toast(next)
            }
            Special.PROFILE_MENU -> main.post {
                if (!menu.canShow()) {
                    toast("Allow \"display over other apps\" for SC Mapper to use the profile menu")
                } else if (!menu.isShowing) {
                    menuOpen = true
                    engine.post { hub.releaseAll() }
                    menu.show(app.profiles.names(), app.profiles.activeName)
                }
            }
            Special.TOGGLE_GYRO -> {
                gyroEnabled = !gyroEnabled
                entries.values.forEach { it.mapper.gyroEnabled = gyroEnabled }
                e.mapper.confirm(if (gyroEnabled) 2 else 1)
                toast(if (gyroEnabled) "Gyro on" else "Gyro off")
            }
        }
    }

    private fun toast(text: String) {
        if (!app.settings.toasts) return
        main.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    // ------------------------------------------------------------------ API for the UI

    class ControllerInfo(val id: String, val label: String, val slot: Int, val battery: Int, val model: ControllerModel)

    fun controllers(): List<ControllerInfo> = entries.values.map {
        ControllerInfo(it.link.id, it.link.label, it.slot, it.link.state.battery, it.link.model)
    }.sortedBy { it.slot }

    fun stateText(id: String): String? = entries[id]?.link?.state?.describe()

    fun rescanUsb() = usb.scan()

    fun calibrateGyro() = engine.post { entries.values.forEach { it.mapper.calibrateGyro(SystemClock.uptimeMillis()) } }

    fun testHaptics() = engine.post { entries.values.forEach { it.mapper.confirm(3) } }

    fun setIdentityFromSettings() = engine.post { hub.setIdentity(app.settings.identity) }

    fun setGenericEnabled(key: String, enabled: Boolean) {
        app.settings.genericDevices = if (enabled) app.settings.genericDevices + key else app.settings.genericDevices - key
        generic.refresh()
    }

    fun connectBle(address: String) {
        app.settings.bleAddresses = app.settings.bleAddresses + address
        ble.connect(address)
    }

    fun forgetBle(address: String) {
        app.settings.bleAddresses = app.settings.bleAddresses - address
        ble.disconnect(address)
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL = "bridge"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MS = 8L
        const val ACTION_STOP = "dev.scmapper.STOP"
        const val ACTION_RESCAN = "dev.scmapper.RESCAN"

        @Volatile var instance: BridgeService? = null
            private set

        /** Why the last start attempt failed, for the UI. */
        @Volatile var lastStartError: String? = null

        fun start(context: Context, action: String? = null) {
            val i = Intent(context, BridgeService::class.java).setAction(action)
            context.startForegroundService(i)
        }
    }
}
