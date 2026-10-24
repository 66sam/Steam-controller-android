package dev.scmapper.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import dev.scmapper.App
import dev.scmapper.controller.BleControllerManager
import dev.scmapper.mapping.Presets
import dev.scmapper.mapping.Profile
import dev.scmapper.output.HidDescriptors.GamepadIdentity
import dev.scmapper.output.VirtualDevices
import dev.scmapper.service.BridgeService
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private val app get() = application as App
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusForm: Form
    private lateinit var profilesForm: Form
    private lateinit var settingsForm: Form
    private var lastStatusKey = ""

    private val refresher = object : Runnable {
        override fun run() {
            renderStatus()
            handler.postDelayed(this, 1000)
        }
    }
    private val profilesChanged: () -> Unit = { runOnUiThread { renderProfiles() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = Form(this, content).dp(12)
        content.setPadding(pad, pad, pad, pad * 3)
        fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }.also { content.addView(it) }
        statusForm = Form(this, section())
        profilesForm = Form(this, section())
        settingsForm = Form(this, section())
        setContentView(ScrollView(this).apply { addView(content) })

        requestRuntimePermissions()
        renderProfiles()
        renderSettings()
        app.profiles.addListener(profilesChanged)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Launched by plugging in a controller or the receiver: make sure the service picks it up.
        if (intent?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) startBridge(BridgeService.ACTION_RESCAN)
    }

    override fun onResume() {
        super.onResume()
        lastStatusKey = ""
        handler.post(refresher)
        renderSettings()
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        app.profiles.removeListener(profilesChanged)
        super.onDestroy()
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) startBridge()
        else requestPermissions(wanted.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) startBridge()
    }

    private fun startBridge(action: String? = null) {
        try {
            BridgeService.start(this, action)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start the service: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ status

    private fun renderStatus() {
        val service = BridgeService.instance
        val controllers = service?.controllers().orEmpty()
        val key = listOf(
            service != null, service?.devices?.status, service?.devices?.lastError, Shizuku.pingBinder(),
            controllers.joinToString { "${it.id}/${it.slot}/${it.battery}" }, app.settings.bleAddresses,
            service?.generic?.available?.joinToString { it.key }, app.settings.genericDevices, service?.generic?.lastError,
        ).toString()
        if (key == lastStatusKey) return
        lastStatusKey = key

        val f = statusForm
        f.clear()
        f.header("SC Mapper")
        f.note("Steam Controller (2015), Steam Controller (2026) and other gamepads → remappable Android gamepad, keyboard and mouse.")

        f.subheader("Virtual devices")
        when {
            service == null -> {
                f.note(
                    "Service is stopped." + (BridgeService.lastStartError?.let {
                        "\nLast start failed: $it\nAllow the \"Nearby devices\" permission and try again."
                    } ?: "")
                )
                f.button("Start") { requestRuntimePermissions() }
            }
            else -> {
                val vd = service.devices
                when (vd.status) {
                    VirtualDevices.Status.READY -> f.note("✅ Ready (helper running through Shizuku).")
                    VirtualDevices.Status.SHIZUKU_MISSING -> {
                        f.note(
                            "⚠️ Shizuku isn't running. SC Mapper needs Shizuku to create a virtual controller that games " +
                                "can see. Install Shizuku, start it (wireless debugging or root), then come back."
                        )
                        f.buttons(
                            "Get Shizuku" to { openUrl("https://shizuku.rikka.app/download/") },
                            "Retry" to { vd.bind(); lastStatusKey = "" },
                        )
                    }
                    VirtualDevices.Status.NO_PERMISSION -> {
                        f.note("⚠️ SC Mapper needs Shizuku permission.")
                        f.button("Grant Shizuku permission") { vd.requestPermission() }
                    }
                    VirtualDevices.Status.CONNECTING -> f.note("Starting helper…")
                    VirtualDevices.Status.ERROR -> {
                        f.note("❌ ${vd.lastError}")
                        f.button("Retry") { vd.bind(); lastStatusKey = "" }
                    }
                }
            }
        }

        f.subheader("Controllers")
        if (controllers.isEmpty()) f.note("None connected. Plug in the controller or the wireless receiver, or add a Bluetooth controller.")
        for (c in controllers) {
            val battery = if (c.battery >= 0) " · battery ${c.battery}%" else ""
            f.row("Player ${c.slot + 1}: ${c.label}", "Tap for live input view$battery") {
                startActivity(Intent(this, MonitorActivity::class.java).putExtra(MonitorActivity.EXTRA_ID, c.id))
            }
        }
        if (service != null) {
            f.buttons(
                "Add Bluetooth" to { addBluetooth() },
                "Rescan USB" to { service.rescanUsb() },
            )
            f.buttons(
                "Calibrate gyro" to {
                    Toast.makeText(this, "Put the controller down on a flat surface…", Toast.LENGTH_SHORT).show()
                    service.calibrateGyro()
                },
                "Test haptics" to { service.testHaptics() },
            )
            for (addr in app.settings.bleAddresses) {
                f.row("Bluetooth: $addr", "Remembered — reconnects automatically. Tap to forget.") {
                    AlertDialog.Builder(this).setMessage("Forget $addr?")
                        .setPositiveButton("Forget") { _, _ -> service.forgetBle(addr); lastStatusKey = "" }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
            renderGeneric(f, service)
            f.button("Stop service") {
                startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP))
                handler.postDelayed({ lastStatusKey = "" }, 300)
            }
        }
    }

    private fun renderGeneric(f: Form, service: BridgeService) {
        f.subheader("Other controllers")
        val available = service.generic.available
        if (available.isEmpty()) {
            f.note("Any other gamepad connected to this device (Xbox, PlayStation, Switch, 8BitDo, …) shows up here.")
        }
        val enabled = app.settings.genericDevices
        for (d in available) {
            val on = d.key in enabled
            f.row(
                (if (on) "☑ " else "☐ ") + d.name,
                if (on) "Remapped by SC Mapper · long-press to set up buttons" else "Not used · tap to remap it with SC Mapper",
                onLongClick = {
                    startActivity(Intent(this, ControllerSetupActivity::class.java).putExtra(ControllerSetupActivity.EXTRA_KEY, d.key))
                },
            ) {
                if (on) {
                    service.setGenericEnabled(d.key, false)
                    lastStatusKey = ""
                } else {
                    AlertDialog.Builder(this).setTitle(d.name)
                        .setMessage(
                            "SC Mapper will take this controller over: games then see SC Mapper's virtual controller " +
                                "instead, with your profile applied. Untick it to give it back."
                        )
                        .setPositiveButton("Use it") { _, _ -> service.setGenericEnabled(d.key, true); lastStatusKey = "" }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
            }
        }
        service.generic.lastError?.let { f.note("❌ $it") }
        f.button("Refresh controller list") { service.generic.refresh(); handler.postDelayed({ lastStatusKey = "" }, 800) }
    }

    private fun addBluetooth() {
        val service = BridgeService.instance ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_PERMISSIONS)
            return
        }
        val steam = service.ble.bondedControllers()
        val others = service.ble.allBonded().filter { d -> steam.none { it.address == d.address } }
        val devices = steam + others
        val labels = devices.map { (if (it in steam) "🎮 " else "") + BleControllerManager.safeName(it) + "  (" + it.address + ")" }
        val builder = AlertDialog.Builder(this).setTitle("Paired Bluetooth devices")
        if (devices.isEmpty()) {
            builder.setMessage(BLE_HELP)
        } else {
            builder.setItems(labels.toTypedArray()) { _, which -> service.connectBle(devices[which].address); lastStatusKey = "" }
        }
        builder.setNeutralButton("Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ profiles

    private fun renderProfiles() {
        val f = profilesForm
        f.clear()
        f.header("Profiles")
        f.note(
            "Import also accepts Steam Input configurations (.vdf), e.g. from steaminputdb.com. " +
                "Tap to activate, long-press to edit. On the controller: Steam + RB / LB to cycle, Steam + ◀ or ▶ " +
                "(or the … button on the 2026 controller) for the menu."
        )
        val active = app.profiles.activeName
        for (p in app.profiles.list()) {
            val on = p.name == active
            f.row((if (on) "● " else "○ ") + p.name, summary(p), onLongClick = { edit(p.name) }) {
                app.profiles.activeName = p.name
            }
        }
        f.buttons(
            "Edit active" to { edit(app.profiles.activeName) },
            "New profile" to { newProfile() },
        )
        f.buttons(
            "Import (.json / Steam .vdf)…" to {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT,
                )
            },
            "Restore presets" to {
                AlertDialog.Builder(this).setMessage("Re-create the built-in profiles? Edited copies with the same names are overwritten.")
                    .setPositiveButton("Restore") { _, _ -> app.profiles.restorePresets() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            },
        )
    }

    private fun summary(p: Profile): String {
        val parts = ArrayList<String>()
        parts += "Right pad: ${p.rightPad.mode.label.lowercase()}"
        parts += "Left pad: ${p.leftPad.mode.label.lowercase()}"
        if (p.gyro.mode.name != "OFF") parts += "Gyro: ${p.gyro.mode.label.lowercase()}"
        return parts.joinToString(" · ")
    }

    private fun edit(name: String) {
        startActivity(Intent(this, ProfileEditorActivity::class.java).putExtra(ProfileEditorActivity.EXTRA_NAME, name))
    }

    private fun newProfile() {
        val bases = listOf("Empty") + Presets.all().map { "Copy of preset: ${it.name}" } + app.profiles.names().map { "Copy of: $it" }
        AlertDialog.Builder(this).setTitle("Start from")
            .setItems(bases.toTypedArray()) { _, which ->
                val presets = Presets.all()
                val base: Profile = when {
                    which == 0 -> Profile("New profile")
                    which <= presets.size -> presets[which - 1]
                    else -> app.profiles.get(app.profiles.names()[which - 1 - presets.size]) ?: Profile("New profile")
                }
                askName(app.profiles.uniqueName(if (which == 0) "New profile" else base.name + " (custom)")) { name ->
                    app.profiles.save(base.copy(name))
                    edit(name)
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun askName(initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSelection(initial.length) }
        AlertDialog.Builder(this).setTitle("Profile name").setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || app.profiles.exists(name)) Toast.makeText(this, "Name empty or already used", Toast.LENGTH_SHORT).show()
                else onOk(name)
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) {
            try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                val (p, notes) = app.profiles.import(text, displayName(uri))
                val message = if (notes.isEmpty()) "Imported without changes." else "Imported, with these limitations:\n\n• " + notes.joinToString("\n• ")
                AlertDialog.Builder(this).setTitle("Imported \"${p.name}\"").setMessage(message)
                    .setPositiveButton("Edit") { _, _ -> edit(p.name) }
                    .setNegativeButton(android.R.string.ok, null).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ settings and help

    private fun renderSettings() {
        val f = settingsForm
        f.clear()
        f.header("Settings")
        f.choice("Virtual controller type", GamepadIdentity.entries.toList(), app.settings.identity, { it.title }) {
            app.settings.identity = it
            BridgeService.instance?.setIdentityFromSettings()
            renderSettings()
        }
        f.check("Show a toast when switching profiles", app.settings.toasts) { app.settings.toasts = it }
        if (!Settings.canDrawOverlays(this)) {
            f.note("The on-controller profile menu needs \"display over other apps\".")
            f.button("Allow display over other apps") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        f.button("Diagnostics (log for troubleshooting)") { startActivity(Intent(this, DiagnosticsActivity::class.java)) }

        f.header("Controller shortcuts")
        f.note(
            "Steam + RB / LB — next / previous profile\n" +
                "Steam + ◀ or ▶ — profile menu (navigate with stick, left pad or d-pad, A selects, B closes)\n" +
                "… (quick access, 2026) — profile menu in the built-in presets\n" +
                "Steam + Y — gyro on / off\n" +
                "Steam + X — calibrate gyro (hold still for a second)\n" +
                "Hold Steam 3 s — turn the controller off (2015)\n" +
                "Tap Steam alone — the Steam button's own binding (Guide by default)"
        )
        f.header("Setup")
        f.note(
            "1. Install and start Shizuku (Wireless debugging, or root). Grant SC Mapper access when asked.\n" +
                "2. USB: plug in the controller or its wireless receiver (USB-C / OTG adapter) and accept the prompt.\n" +
                "3. Bluetooth: " + BLE_HELP
        )
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_IMPORT = 2
        const val BLE_HELP = "Steam Controller (2026): put it in Bluetooth pairing mode, pair it in Android's Bluetooth " +
            "settings, then use \"Add Bluetooth\" here.\n" +
            "Steam Controller (2015): it needs Valve's Bluetooth (BLE) firmware, installed once from Steam on a PC " +
            "(Big Picture → Settings → Controller → your Steam Controller → firmware update). Hold Y while pressing the " +
            "Steam button to enter pairing mode, pair it in Android's Bluetooth settings, then use \"Add Bluetooth\" here."
    }
}
