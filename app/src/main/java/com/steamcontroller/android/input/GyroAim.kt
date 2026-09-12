package com.steamcontroller.android.input

import kotlin.math.abs

/**
 * Turns the SC2026's IMU stream into right-stick deflection — "gyro aiming".
 *
 * The controller reports ORIENTATION (a unit quaternion), not rotation rate, so this
 * differentiates. The relative rotation between two consecutive samples is
 * conj(previous) * current; at 80+ Hz the angle between samples is small, and for small
 * angles the vector part of that delta is approximately half the rotation vector. Dividing
 * by the elapsed time gives angular velocity in rad/s, which is the quantity that maps
 * naturally onto a stick: how fast you twist is how far the stick deflects.
 *
 * Working from orientation deltas rather than integrating absolute orientation also means
 * there is no accumulated drift to re-zero — put the controller down and the output falls
 * to zero on its own.
 */
class GyroAim {

    companion object {
        /**
         * rad/s → stick units at sensitivity 1.0. A brisk wrist flick is roughly 3 rad/s, so
         * this puts that near full deflection while leaving headroom above.
         */
        private const val RATE_TO_STICK = 9000f

        /**
         * Rotation below this is treated as zero. MEMS gyros output a small non-zero rate
         * even at rest; without this the camera creeps while the controller sits still.
         */
        private const val NOISE_FLOOR_RAD_S = 0.02f

        /** Guard against a stale timestamp after a pause producing one enormous jump. */
        private const val MAX_DT_MS = 100L
    }

    private var have = false
    private var lw = 0f
    private var lx = 0f
    private var ly = 0f
    private var lz = 0f
    private var lastT = 0L

    /** Forget history so re-activating never emits the rotation that happened while inactive. */
    fun reset() {
        have = false
        lastT = 0L
    }

    /**
     * Feed one frame. Returns (yawRate, pitchRate) in rad/s — yaw is the turn that should
     * drive stick X, pitch the tilt that should drive stick Y.
     */
    fun update(qw: Short, qx: Short, qy: Short, qz: Short, nowMs: Long): Pair<Float, Float> {
        val w = qw / 32767f
        val x = qx / 32767f
        val y = qy / 32767f
        val z = qz / 32767f

        if (!have) {
            store(w, x, y, z, nowMs)
            return 0f to 0f
        }

        val dtMs = (nowMs - lastT).coerceIn(1L, MAX_DT_MS)
        val dt = dtMs / 1000f

        // delta = conj(last) * current
        val dw = lw * w + lx * x + ly * y + lz * z
        var dx = lw * x - w * lx - ly * z + lz * y
        var dy = lw * y - w * ly - lz * x + lx * z
        var dz = lw * z - w * lz - lx * y + ly * x

        // A quaternion and its negation are the same rotation; pick the shortest arc so a
        // sign flip in the source stream is not read as a violent spin.
        if (dw < 0f) {
            dx = -dx; dy = -dy; dz = -dz
        }

        store(w, x, y, z, nowMs)

        val yaw = 2f * dz / dt
        val pitch = 2f * dx / dt
        return squelch(yaw) to squelch(pitch)
    }

    private fun squelch(v: Float): Float = if (abs(v) < NOISE_FLOOR_RAD_S) 0f else v

    private fun store(w: Float, x: Float, y: Float, z: Float, t: Long) {
        lw = w; lx = x; ly = y; lz = z
        lastT = t
        have = true
    }

    /** Convert a rate in rad/s to stick units for the given sensitivity multiplier. */
    fun toStick(rate: Float, sensitivity: Float): Int =
        (rate * sensitivity * RATE_TO_STICK).toInt().coerceIn(-32767, 32767)
}
