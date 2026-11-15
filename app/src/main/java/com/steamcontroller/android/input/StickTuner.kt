package com.steamcontroller.android.input

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Works out a stick's calibration from three short recordings of raw values:
 *
 *  1. **rest**    — stick released: where centre really is, and how much it jitters.
 *  2. **sweep**   — stick rotated around its edge: how far it reaches in each direction.
 *  3. **release** — stick released again: how precisely it springs back to centre.
 *
 * The goal is the feel of a pad that sends nearly raw input, like a DualSense: the smallest
 * deadzone that still never drifts, and full output at the deflection the stick can really
 * reach in its weakest direction, with a straight line in between.
 */
object StickTuner {

    private const val FULL = 32767.0
    private const val BINS = 36                     // 10° each
    private const val MIN_COVERAGE_BINS = 30        // ~300° of the circle
    private const val DEADZONE_MARGIN_PCT = 1.5     // above the worst measured noise
    private const val MIN_DEADZONE_PCT = 2
    private const val MAX_DEADZONE_PCT = 30
    private const val OUTER_MARGIN_PCT = 2          // below the weakest direction's reach
    private const val MIN_OUTER_PCT = 60
    private const val MOVED_AT_REST_PCT = 15.0
    /** Leading share of a rest recording dropped as settling time (thumb leaving, spring-back). */
    private const val SETTLE_FRACTION = 0.3

    data class Result(
        val calibration: StickCalibration,
        val centerOffsetPct: Double,
        val jitterPct: Double,
        val returnErrorPct: Double,
        val reachWeakestPct: Double,
        val reachTypicalPct: Double,
        val coverageDegrees: Int,
        /** Non-null when the recording cannot be trusted; nothing should be applied. */
        val problem: String?,
    )

    fun analyze(
        restRaw: List<IntArray>,
        sweep: List<IntArray>,
        releaseRaw: List<IntArray>,
        current: StickCalibration,
    ): Result {
        // The first part of each rest recording is the stick still moving: the thumb only
        // just left it, or it is springing back from the edge after the sweep. Counting
        // those samples made the release check fail every time ("still being held").
        val rest = settled(restRaw)
        val release = settled(releaseRaw)
        if (rest.size < 5 || sweep.size < 20 || release.size < 5) {
            return failed(current, "Not enough data from the controller. Is it connected and the service running?")
        }

        val cx = median(rest.map { it[0] })
        val cy = median(rest.map { it[1] })

        val restSpread = percentile(rest.map { hypot((it[0] - cx).toDouble(), (it[1] - cy).toDouble()) }, 0.95)
        if (restSpread / FULL * 100 > MOVED_AT_REST_PCT) {
            return failed(current, "The stick moved during the first step. Let go of both sticks completely and try again.")
        }

        val rx = median(release.map { it[0] })
        val ry = median(release.map { it[1] })
        val returnError = hypot((rx - cx).toDouble(), (ry - cy).toDouble())
        val releaseSpread = percentile(release.map { hypot((it[0] - rx).toDouble(), (it[1] - ry).toDouble()) }, 0.95)
        if ((returnError + releaseSpread) / FULL * 100 > MOVED_AT_REST_PCT) {
            return failed(current, "The stick was still being held in the last step. Let go of both sticks and try again.")
        }

        // Worst case of: jitter at rest, and where it lands after springing back.
        val noise = maxOf(restSpread, returnError + releaseSpread)
        val deadzone = ceil(noise / FULL * 100 + DEADZONE_MARGIN_PCT).toInt()
            .coerceIn(MIN_DEADZONE_PCT, MAX_DEADZONE_PCT)

        val maxPerBin = DoubleArray(BINS)
        for (s in sweep) {
            val dx = (s[0] - cx).toDouble()
            val dy = (s[1] - cy).toDouble()
            val mag = hypot(dx, dy)
            if (mag < FULL * 0.25) continue // passing through the middle, not the edge
            val angle = (Math.toDegrees(atan2(dy, dx)) + 360.0) % 360.0
            val bin = (angle / (360.0 / BINS)).toInt().coerceIn(0, BINS - 1)
            if (mag > maxPerBin[bin]) maxPerBin[bin] = mag
        }
        val reached = maxPerBin.filter { it > 0 }
        val coverage = reached.size * (360 / BINS)
        if (reached.size < MIN_COVERAGE_BINS) {
            return failed(
                current,
                "The stick only covered about $coverage° of its edge. Rotate it all the way round, slowly, " +
                    "three times during the sweep.",
                coverage,
            )
        }

        // Weakest real direction (10th percentile, so one odd sample cannot decide it).
        val weakest = percentile(reached, 0.10)
        val typical = percentile(reached, 0.50)
        val outer = (floor(weakest / FULL * 100).toInt() - OUTER_MARGIN_PCT)
            .coerceIn(MIN_OUTER_PCT, 100)
            .coerceAtLeast(deadzone + 10)

        return Result(
            calibration = current.copy(
                centerX = cx,
                centerY = cy,
                deadzonePercent = deadzone,
                outerPercent = outer,
            ),
            centerOffsetPct = hypot(cx.toDouble(), cy.toDouble()) / FULL * 100,
            jitterPct = restSpread / FULL * 100,
            returnErrorPct = (returnError + releaseSpread) / FULL * 100,
            reachWeakestPct = weakest / FULL * 100,
            reachTypicalPct = typical / FULL * 100,
            coverageDegrees = coverage,
            problem = null,
        )
    }

    private fun failed(current: StickCalibration, why: String, coverage: Int = 0) = Result(
        calibration = current,
        centerOffsetPct = 0.0, jitterPct = 0.0, returnErrorPct = 0.0,
        reachWeakestPct = 0.0, reachTypicalPct = 0.0,
        coverageDegrees = coverage,
        problem = why,
    )

    private fun settled(samples: List<IntArray>): List<IntArray> =
        samples.drop((samples.size * SETTLE_FRACTION).toInt())

    /** Raw numbers behind a verdict, for the log. */
    fun diagnostics(rest: List<IntArray>, release: List<IntArray>): String {
        val r = settled(rest); val l = settled(release)
        if (r.isEmpty() || l.isEmpty()) return "no samples"
        val cx = median(r.map { it[0] }); val cy = median(r.map { it[1] })
        val rx = median(l.map { it[0] }); val ry = median(l.map { it[1] })
        val rs = percentile(r.map { hypot((it[0] - cx).toDouble(), (it[1] - cy).toDouble()) }, 0.95)
        val ls = percentile(l.map { hypot((it[0] - rx).toDouble(), (it[1] - ry).toDouble()) }, 0.95)
        return "centre=($cx,$cy) restSpread=%.1f%% release=($rx,$ry) releaseSpread=%.1f%% returnErr=%.1f%%".format(
            rs / FULL * 100, ls / FULL * 100, hypot((rx - cx).toDouble(), (ry - cy).toDouble()) / FULL * 100,
        )
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    private fun percentile(values: List<Double>, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]
    }
}
