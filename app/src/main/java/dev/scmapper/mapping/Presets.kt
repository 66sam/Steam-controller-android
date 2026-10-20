package dev.scmapper.mapping

import dev.scmapper.mapping.GamepadOut as G

/** Built-in profiles, modelled on the templates that ship with Steam. */
object Presets {
    fun all(): List<Profile> = listOf(gamepad(), gamepadGyroFps(), keyboardMouse(), fpsKeyboardMouse(), retro())

    private fun dpad(requireClick: Boolean) = SurfaceConfig(
        mode = SurfaceMode.DIRECTIONAL,
        requireClick = requireClick,
        eightWay = true,
        deadzone = 0.25f,
        dirActions = mutableListOf(Action.gp(G.DPAD_UP), Action.gp(G.DPAD_DOWN), Action.gp(G.DPAD_LEFT), Action.gp(G.DPAD_RIGHT)),
    )

    private fun Profile.xboxFace() {
        binding(Btn.A).press = Action.gp(G.A)
        binding(Btn.B).press = Action.gp(G.B)
        binding(Btn.X).press = Action.gp(G.X)
        binding(Btn.Y).press = Action.gp(G.Y)
        binding(Btn.LB).press = Action.gp(G.LB)
        binding(Btn.RB).press = Action.gp(G.RB)
        binding(Btn.BACK).press = Action.gp(G.BACK)
        binding(Btn.START).press = Action.gp(G.START)
        binding(Btn.STEAM).press = Action.gp(G.GUIDE)
        binding(Btn.STICK_CLICK).press = Action.gp(G.L3)
        leftTrigger = TriggerConfig(analog = TriggerTarget.LEFT)
        rightTrigger = TriggerConfig(analog = TriggerTarget.RIGHT)
    }

    /** Xbox-style gamepad; the right pad is the right analog stick. */
    fun gamepad() = Profile("Gamepad").apply {
        xboxFace()
        binding(Btn.LGRIP).press = Action.gp(G.L3)
        binding(Btn.RGRIP).press = Action.gp(G.R3)
        stick = SurfaceConfig(mode = SurfaceMode.JOYSTICK, target = StickTarget.LEFT, deadzone = 0.08f)
        leftPad = dpad(requireClick = true)
        rightPad = SurfaceConfig(
            mode = SurfaceMode.JOYSTICK, target = StickTarget.RIGHT, deadzone = 0.05f, sensitivity = 1.3f,
            clickAction = Action.gp(G.R3),
        )
    }

    /** Gamepad with gyro aiming on the right stick. Hold the right paddle to recenter (ratchet). */
    fun gamepadGyroFps() = gamepad().apply {
        name = "FPS · Gamepad + gyro aim"
        binding(Btn.RGRIP).press = Action.NONE
        gyro = GyroConfig(
            mode = GyroMode.STICK, target = StickTarget.RIGHT,
            activation = GyroActivation.WHILE_NOT_HELD, button = Btn.RGRIP,
            sensX = 1.0f, sensY = 0.8f, antiDeadzone = 0.18f,
        )
    }

    /** Desktop-style keyboard & mouse, like Steam's desktop configuration, with Android shortcuts. */
    fun keyboardMouse() = Profile("Keyboard & Mouse").apply {
        binding(Btn.A).press = Action.key(40) // Enter
        binding(Btn.B).press = Action.media(0x224) // Android Back
        binding(Btn.X).press = Action.key(42) // Backspace
        binding(Btn.Y).press = Action.key(44) // Space
        binding(Btn.LB).press = Action.key(225) // Shift
        binding(Btn.RB).press = Action.key(224) // Ctrl
        binding(Btn.BACK).press = Action.key(43) // Tab
        binding(Btn.START).press = Action.key(41) // Escape
        binding(Btn.STEAM).press = Action.media(0x223) // Android Home
        binding(Btn.LGRIP).press = Action.media(0x0EA) // Volume down
        binding(Btn.RGRIP).press = Action.media(0x0E9) // Volume up
        binding(Btn.STICK_CLICK).press = Action.mouse(MouseOut.MIDDLE)
        leftTrigger = TriggerConfig(fullAction = Action.mouse(MouseOut.RIGHT))
        rightTrigger = TriggerConfig(fullAction = Action.mouse(MouseOut.LEFT))
        stick = SurfaceConfig(
            mode = SurfaceMode.DIRECTIONAL, eightWay = true, deadzone = 0.4f,
            dirActions = mutableListOf(Action.key(82), Action.key(81), Action.key(80), Action.key(79)),
        )
        leftPad = SurfaceConfig(mode = SurfaceMode.SCROLL, scrollStepDeg = 25f, clickAction = Action.mouse(MouseOut.MIDDLE))
        rightPad = SurfaceConfig(mode = SurfaceMode.MOUSE, sensitivity = 1.0f, trackball = true, friction = 0.85f, clickAction = Action.mouse(MouseOut.LEFT))
    }

    /** Keyboard & mouse shooter layout with gyro mouse while the right pad is touched. */
    fun fpsKeyboardMouse() = Profile("FPS · Keyboard & Mouse + gyro").apply {
        binding(Btn.A).press = Action.key(44) // Space: jump
        binding(Btn.B).press = Action.key(6) // C: crouch
        binding(Btn.X).press = Action.key(21) // R: reload
        binding(Btn.Y).press = Action.key(8) // E: use
        binding(Btn.LB).press = Action.key(20) // Q
        binding(Btn.RB).press = Action.key(9) // F
        binding(Btn.BACK).press = Action.key(43) // Tab
        binding(Btn.START).press = Action.key(41) // Escape
        binding(Btn.STEAM).press = Action.media(0x223)
        binding(Btn.LGRIP).press = Action.key(225) // Shift: sprint
        binding(Btn.RGRIP).press = Action.key(29) // Z
        binding(Btn.STICK_CLICK).press = Action.key(225)
        leftTrigger = TriggerConfig(softAction = Action.mouse(MouseOut.RIGHT), softThreshold = 0.3f)
        rightTrigger = TriggerConfig(softAction = Action.mouse(MouseOut.LEFT), softThreshold = 0.3f)
        stick = SurfaceConfig(
            mode = SurfaceMode.DIRECTIONAL, eightWay = true, deadzone = 0.3f,
            dirActions = mutableListOf(Action.key(26), Action.key(22), Action.key(4), Action.key(7)), // W S A D
        )
        leftPad = SurfaceConfig(
            mode = SurfaceMode.DIRECTIONAL, requireClick = true, eightWay = false,
            dirActions = mutableListOf(Action.key(30), Action.key(32), Action.key(31), Action.key(33)), // 1 3 2 4
        )
        rightPad = SurfaceConfig(mode = SurfaceMode.MOUSE, sensitivity = 1.2f, trackball = true, friction = 0.9f, clickAction = Action.mouse(MouseOut.MIDDLE))
        gyro = GyroConfig(mode = GyroMode.MOUSE, activation = GyroActivation.WHILE_HELD, button = Btn.RPAD_TOUCH, sensX = 1.2f, sensY = 1.0f)
    }

    /** Pads as a D-pad and a face-button pad, for emulators and retro games. */
    fun retro() = Profile("Retro · Pads as D-pad + ABXY").apply {
        xboxFace()
        binding(Btn.LGRIP).press = Action.gp(G.LB)
        binding(Btn.RGRIP).press = Action.gp(G.RB)
        stick = SurfaceConfig(mode = SurfaceMode.JOYSTICK, target = StickTarget.LEFT, deadzone = 0.08f)
        leftPad = dpad(requireClick = false).apply { deadzone = 0.2f }
        rightPad = SurfaceConfig(
            mode = SurfaceMode.DIRECTIONAL, requireClick = true, eightWay = false, deadzone = 0.15f,
            dirActions = mutableListOf(Action.gp(G.Y), Action.gp(G.A), Action.gp(G.X), Action.gp(G.B)),
        )
    }
}
