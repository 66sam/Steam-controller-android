package dev.scmapper.controller

import dev.scmapper.mapping.Btn

/**
 * Latest decoded state of one physical controller, in a controller-independent form.
 * [buttons] holds [Btn.bit]s. Axes are signed 16 bit with Y up positive; triggers are 0..255.
 * Gyro follows SDL's convention in ±2000 °/s full scale: X = pitch (nose up positive),
 * Y = roll, Z = yaw (turning left positive).
 */
class RawState {
    var buttons = 0
    var lt = 0
    var rt = 0
    var stickX = 0
    var stickY = 0
    var rstickX = 0
    var rstickY = 0
    var lpadX = 0
    var lpadY = 0
    var rpadX = 0
    var rpadY = 0
    var accelX = 0
    var accelY = 0
    var accelZ = 0
    var gyroX = 0
    var gyroY = 0
    var gyroZ = 0
    var battery = -1

    fun pressed(b: Btn) = buttons and b.bit != 0

    fun copyFrom(o: RawState) {
        buttons = o.buttons; lt = o.lt; rt = o.rt
        stickX = o.stickX; stickY = o.stickY; rstickX = o.rstickX; rstickY = o.rstickY
        lpadX = o.lpadX; lpadY = o.lpadY; rpadX = o.rpadX; rpadY = o.rpadY
        accelX = o.accelX; accelY = o.accelY; accelZ = o.accelZ
        gyroX = o.gyroX; gyroY = o.gyroY; gyroZ = o.gyroZ
        battery = o.battery
    }

    fun copy() = RawState().also { it.copyFrom(this) }

    fun describe(): String = buildString {
        append("pressed: ").append(Btn.entries.filter { pressed(it) }.joinToString(" ") { it.name }.ifEmpty { "—" })
        append("\nLT=").append(lt).append("  RT=").append(rt)
        append("\nleft stick=").append(stickX).append(',').append(stickY)
        append("\nright stick=").append(rstickX).append(',').append(rstickY)
        append("\nleft pad=").append(lpadX).append(',').append(lpadY)
        append("\nright pad=").append(rpadX).append(',').append(rpadY)
        append("\ngyro p/r/y=").append(gyroX).append(',').append(gyroY).append(',').append(gyroZ)
        append("\naccel=").append(accelX).append(',').append(accelY).append(',').append(accelZ)
        if (battery >= 0) append("\nbattery=").append(battery).append('%')
    }
}
