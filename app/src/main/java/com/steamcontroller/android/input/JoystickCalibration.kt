package com.steamcontroller.android.input

import kotlin.math.sqrt

// Per-stick calibration applied to raw SC2026 Int16 values (-32768..32767)
// before they go to uinput / GamepadMapper.
data class StickCalibration(
    val centerX: Int = 0,
    val centerY: Int = 0,
    val deadzonePercent: Int = 8,   // 0..100 — radial deadzone as % of full range
    val invertY: Boolean = false,
    /**
     * Deflection, as % of full range, that already counts as fully pushed. Sticks rarely
     * reach the last few percent of their range in every direction, so without this some
     * directions never produce full output — which feels like a slow curve at the edge.
     * Set by Auto-tune sticks from what the stick actually reached; 100 = off.
     */
    val outerPercent: Int = 100,
) {
    // Returns calibrated (x, y) in the same Int16 range: radial deadzone, then a linear
    // ramp from the deadzone edge to the outer edge, clamped to the unit circle.
    fun apply(rawX: Int, rawY: Int): Pair<Int, Int> {
        // 1. Subtract center offset
        val x = rawX - centerX
        var y = rawY - centerY

        // 2. Invert Y if requested
        if (invertY) y = -y

        // 3. Linear ramp between the two radii, keeping direction. Applied to the
        //    magnitude rather than per axis so diagonals behave like straight lines.
        val mag = sqrt((x.toDouble() * x) + (y.toDouble() * y))
        if (mag <= 0.0) return 0 to 0
        val inner = FULL * deadzonePercent.coerceIn(0, 95) / 100.0
        val outer = maxOf(FULL * outerPercent.coerceIn(1, 100) / 100.0, inner + 1.0)
        if (mag < inner) return 0 to 0
        val outMag = ((mag - inner) / (outer - inner)).coerceAtMost(1.0) * FULL
        val scale = outMag / mag
        return (x * scale).toInt().coerceIn(-32767, 32767) to
            (y * scale).toInt().coerceIn(-32767, 32767)
    }

    companion object {
        private const val FULL = 32767.0
        val DEFAULT = StickCalibration()
    }
}
