package dev.scmapper.output

import dev.scmapper.mapping.GamepadOut
import dev.scmapper.mapping.OutputFrame
import dev.scmapper.output.HidDescriptors.GamepadIdentity

/**
 * Owns the virtual devices and turns [OutputFrame]s into HID reports, sending only what changed.
 * One gamepad per controller slot; keyboard and mouse are shared and created on demand.
 * Call from the engine thread only.
 */
class OutputHub(private val devices: VirtualDevices) {
    var identity = GamepadIdentity.XBOX_ONE_BT
        private set

    private val lastGamepad = HashMap<Int, ByteArray>()
    private val slotFrames = HashMap<Int, OutputFrame>()
    private var lastKeyboard: ByteArray? = null
    private var lastConsumer: ByteArray? = null
    private var lastMouseButtons = 0
    private var mouseRemX = 0f
    private var mouseRemY = 0f
    private var wantKeyboard = false
    private var wantMouse = false

    fun gamepadId(slot: Int) = GAMEPAD_BASE + slot

    fun slotOf(deviceId: Int): Int? = if (deviceId >= GAMEPAD_BASE) deviceId - GAMEPAD_BASE else null

    fun setIdentity(id: GamepadIdentity) {
        if (id == identity) return
        identity = id
        // Recreate gamepads with the new identity.
        for (slot in slotFrames.keys) {
            devices.destroy(gamepadId(slot))
            lastGamepad.remove(slot)
            ensureGamepad(slot)
        }
    }

    fun ensureGamepad(slot: Int) {
        slotFrames.getOrPut(slot) { OutputFrame() }
        if (!devices.isReady || devices.isCreated(gamepadId(slot))) return
        val name = if (slot == 0) identity.deviceName else "${identity.deviceName} ${slot + 1}"
        devices.create(gamepadId(slot), name, identity.vendor, identity.product, identity.bus, HidDescriptors.gamepad(identity))
        lastGamepad.remove(slot)
    }

    fun removeGamepad(slot: Int) {
        slotFrames.remove(slot)
        lastGamepad.remove(slot)
        devices.destroy(gamepadId(slot))
        flushShared()
    }

    /** Creates or removes the keyboard/mouse so that only devices the active profile uses exist. */
    fun setSharedDevices(keyboard: Boolean, mouse: Boolean) {
        wantKeyboard = keyboard
        wantMouse = mouse
        syncSharedDevices()
    }

    /** Called when the helper (re)connects: recreate everything. */
    fun recreateAll() {
        lastGamepad.clear()
        lastKeyboard = null
        lastConsumer = null
        slotFrames.keys.forEach { ensureGamepad(it) }
        syncSharedDevices()
    }

    private fun syncSharedDevices() {
        if (!devices.isReady) return
        if (wantKeyboard && !devices.isCreated(KEYBOARD_ID)) {
            devices.create(KEYBOARD_ID, "SC Mapper Keyboard", 0x28DE, 0x11F0, HidDescriptors.BUS_USB, HidDescriptors.KEYBOARD)
            lastKeyboard = null; lastConsumer = null
        } else if (!wantKeyboard && devices.isCreated(KEYBOARD_ID)) {
            devices.destroy(KEYBOARD_ID)
        }
        if (wantMouse && !devices.isCreated(MOUSE_ID)) {
            devices.create(MOUSE_ID, "SC Mapper Mouse", 0x28DE, 0x11F1, HidDescriptors.BUS_USB, HidDescriptors.MOUSE)
        } else if (!wantMouse && devices.isCreated(MOUSE_ID)) {
            devices.destroy(MOUSE_ID)
        }
    }

    fun submit(slot: Int, f: OutputFrame) {
        val stored = slotFrames.getOrPut(slot) { OutputFrame() }
        copyLevels(f, stored)
        stored.mouseDx += f.mouseDx; stored.mouseDy += f.mouseDy
        stored.wheel += f.wheel; stored.pan += f.pan
        f.mouseDx = 0f; f.mouseDy = 0f; f.wheel = 0; f.pan = 0
        sendGamepad(slot, stored)
        flushShared()
    }

    private fun copyLevels(from: OutputFrame, to: OutputFrame) {
        to.gamepad = from.gamepad
        to.lx = from.lx; to.ly = from.ly; to.rx = from.rx; to.ry = from.ry; to.lt = from.lt; to.rt = from.rt
        to.keys.clear(); to.keys.addAll(from.keys); to.mods = from.mods
        to.consumer.clear(); to.consumer.addAll(from.consumer)
        to.mouseButtons = from.mouseButtons
    }

    private fun sendGamepad(slot: Int, f: OutputFrame) {
        val id = gamepadId(slot)
        if (!devices.isCreated(id)) return
        var b = 0
        fun bit(o: GamepadOut, hid: Int) { if (f.has(o)) b = b or (1 shl (hid - 1)) }
        bit(GamepadOut.A, HidDescriptors.HB_A)
        bit(GamepadOut.B, HidDescriptors.HB_B)
        bit(GamepadOut.X, HidDescriptors.HB_X)
        bit(GamepadOut.Y, HidDescriptors.HB_Y)
        bit(GamepadOut.LB, HidDescriptors.HB_L1)
        bit(GamepadOut.RB, HidDescriptors.HB_R1)
        bit(GamepadOut.START, HidDescriptors.HB_START)
        bit(GamepadOut.L3, HidDescriptors.HB_THUMBL)
        bit(GamepadOut.R3, HidDescriptors.HB_THUMBR)
        val hat = HidDescriptors.hat(f.has(GamepadOut.DPAD_UP), f.has(GamepadOut.DPAD_DOWN), f.has(GamepadOut.DPAD_LEFT), f.has(GamepadOut.DPAD_RIGHT))
        val report = HidDescriptors.gamepadReport(
            identity, f.lx, -f.ly, f.rx, -f.ry, f.lt, f.rt, hat, b,
            select = f.has(GamepadOut.BACK), mode = f.has(GamepadOut.GUIDE),
        )
        if (!report.contentEquals(lastGamepad[slot])) {
            lastGamepad[slot] = report
            devices.send(id, report)
        }
    }

    /** Merges keyboard/mouse output of all controllers. */
    private fun flushShared() {
        if (devices.isCreated(KEYBOARD_ID)) {
            val keys = LinkedHashSet<Int>()
            val consumer = LinkedHashSet<Int>()
            var mods = 0
            for (f in slotFrames.values) { keys += f.keys; consumer += f.consumer; mods = mods or f.mods }
            val kb = HidDescriptors.keyboardReport(mods, keys)
            if (!kb.contentEquals(lastKeyboard)) { lastKeyboard = kb; devices.send(KEYBOARD_ID, kb) }
            val cc = HidDescriptors.consumerReport(consumer)
            if (!cc.contentEquals(lastConsumer)) { lastConsumer = cc; devices.send(KEYBOARD_ID, cc) }
        }
        var buttons = 0
        var dx = mouseRemX
        var dy = mouseRemY
        var wheel = 0
        var pan = 0
        for (f in slotFrames.values) {
            buttons = buttons or f.mouseButtons
            dx += f.mouseDx; dy += f.mouseDy; wheel += f.wheel; pan += f.pan
            f.mouseDx = 0f; f.mouseDy = 0f; f.wheel = 0; f.pan = 0
        }
        if (!devices.isCreated(MOUSE_ID)) {
            mouseRemX = 0f; mouseRemY = 0f
            return
        }
        val ix = dx.toInt()
        val iy = dy.toInt()
        mouseRemX = dx - ix
        mouseRemY = dy - iy
        if (ix != 0 || iy != 0 || wheel != 0 || pan != 0 || buttons != lastMouseButtons) {
            lastMouseButtons = buttons
            devices.send(MOUSE_ID, HidDescriptors.mouseReport(buttons, ix, iy, wheel, pan))
        }
    }

    /** Releases everything (e.g. when the menu opens or the service stops). */
    fun releaseAll() {
        for ((slot, f) in slotFrames) {
            f.clearAll()
            sendGamepad(slot, f)
        }
        flushShared()
    }

    companion object {
        const val KEYBOARD_ID = 1
        const val MOUSE_ID = 2
        const val GAMEPAD_BASE = 10
    }
}
