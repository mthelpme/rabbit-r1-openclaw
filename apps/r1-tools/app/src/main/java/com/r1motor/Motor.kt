package com.r1motor

/** Writes the R1 camera step-motor orientation node (needs root + the sepolicy module). */
object Motor {
    const val NODE = "/sys/devices/platform/step_motor_ms35774/orientation"
    const val FRONT = 0
    const val REAR = 180
    const val STOW = 90

    fun set(pos: Int): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo $pos > $NODE"))
        p.waitFor() == 0
    } catch (e: Exception) { false }
}
