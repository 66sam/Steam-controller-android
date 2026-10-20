package dev.scmapper.mapping

/** Everything one controller outputs in one engine step. Stick Y is up-positive here. */
class OutputFrame {
    /** Bitmask of [GamepadOut] ordinals. */
    var gamepad = 0
    var lx = 0f
    var ly = 0f
    var rx = 0f
    var ry = 0f
    var lt = 0f
    var rt = 0f
    val keys = LinkedHashSet<Int>()
    var mods = 0
    val consumer = LinkedHashSet<Int>()
    var mouseButtons = 0
    /** Relative mouse motion in pixels (screen coordinates, Y down). Accumulates until sent. */
    var mouseDx = 0f
    var mouseDy = 0f
    var wheel = 0
    var pan = 0

    fun has(o: GamepadOut) = gamepad and (1 shl o.ordinal) != 0

    /** Clears the level-based state; relative motion is consumed by the output hub instead. */
    fun clearLevels() {
        gamepad = 0
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
        keys.clear(); mods = 0; consumer.clear(); mouseButtons = 0
    }

    fun clearAll() {
        clearLevels()
        mouseDx = 0f; mouseDy = 0f; wheel = 0; pan = 0
    }
}
