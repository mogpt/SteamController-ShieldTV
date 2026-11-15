package com.steamcontroller.android.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.steamcontroller.android.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Live picture of one stick for Auto-tune:
 *
 *  - the full range (outer circle) with a crosshair,
 *  - the shape the stick has actually reached so far, filled in as it is swept,
 *  - the deadzone (grey disc) and the point of full output (dashed ring), once known,
 *  - the measured resting centre (small cross) and the live position (dot).
 *
 * All inputs are raw Int16 stick values; the view does its own normalisation.
 */
class StickTraceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val green = context.getColor(R.color.nv_green_bright)

    private val rangePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFF3A4048.toInt()
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0x33FFFFFF
    }
    private val reachFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = (green and 0x00FFFFFF) or 0x33000000
    }
    private val reachStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = green
        strokeJoin = Paint.Join.ROUND
    }
    private val deadzonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x55FFFFFF
    }
    private val outerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = 0xFFFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF66C0F4.toInt()
    }
    private val dotHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x4466C0F4
    }

    private val reach = FloatArray(BINS)
    private val path = Path()

    private var liveX = 0f
    private var liveY = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var hasCenter = false
    private var deadzone = 0f
    private var outer = 0f

    /** Latest raw position. */
    fun setLive(rawX: Int, rawY: Int) {
        liveX = (rawX / FULL).coerceIn(-1f, 1f)
        liveY = (rawY / FULL).coerceIn(-1f, 1f)
        invalidate()
    }

    /** Measured resting centre; reach is traced relative to it. */
    fun setCenter(rawX: Int, rawY: Int) {
        centerX = rawX / FULL
        centerY = rawY / FULL
        hasCenter = true
        invalidate()
    }

    /** Record a sweep sample: grows the reached shape in that direction. */
    fun addReach(rawX: Int, rawY: Int) {
        val dx = rawX / FULL - centerX
        val dy = rawY / FULL - centerY
        val mag = hypot(dx, dy)
        if (mag < 0.25f) return
        val angle = ((Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0)
        val bin = (angle / (360.0 / BINS)).toInt().coerceIn(0, BINS - 1)
        if (mag > reach[bin]) {
            reach[bin] = mag.coerceAtMost(1.1f)
            invalidate()
        }
    }

    /** Deadzone and full-output point, as fractions of full range; 0 hides them. */
    fun setZones(deadzoneFraction: Float, outerFraction: Float) {
        deadzone = deadzoneFraction
        outer = outerFraction
        invalidate()
    }

    fun reset() {
        reach.fill(0f)
        hasCenter = false
        centerX = 0f; centerY = 0f
        deadzone = 0f; outer = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val r = width / 2f - 10f * density

        canvas.drawLine(c, c - r, c, c + r, crossPaint)
        canvas.drawLine(c - r, c, c + r, c, crossPaint)
        canvas.drawCircle(c, c, r, rangePaint)

        val ox = c + centerX * r
        val oy = c + centerY * r

        // Reached shape: one point per filled direction, joined in angle order.
        path.reset()
        var first = true
        for (i in 0 until BINS) {
            if (reach[i] <= 0f) continue
            val a = Math.toRadians((i + 0.5) * (360.0 / BINS))
            val px = ox + (cos(a) * reach[i] * r).toFloat()
            val py = oy + (sin(a) * reach[i] * r).toFloat()
            if (first) { path.moveTo(px, py); first = false } else path.lineTo(px, py)
        }
        if (!first) {
            path.close()
            canvas.drawPath(path, reachFill)
            canvas.drawPath(path, reachStroke)
        }

        if (deadzone > 0f) canvas.drawCircle(ox, oy, deadzone * r, deadzonePaint)
        if (outer > 0f) canvas.drawCircle(ox, oy, outer * r, outerPaint)

        if (hasCenter) {
            val k = 6f * density
            canvas.drawLine(ox - k, oy, ox + k, oy, reachStroke)
            canvas.drawLine(ox, oy - k, ox, oy + k, reachStroke)
        }

        val lx = c + liveX * r
        val ly = c + liveY * r
        canvas.drawCircle(lx, ly, 14f * density, dotHalo)
        canvas.drawCircle(lx, ly, 7f * density, dotPaint)
    }

    companion object {
        private const val BINS = 72
        private const val FULL = 32767f
    }
}
