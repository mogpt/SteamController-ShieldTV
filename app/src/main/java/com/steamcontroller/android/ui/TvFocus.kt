package com.steamcontroller.android.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.steamcontroller.android.R

/**
 * D-pad focus that can be seen from the sofa.
 *
 * Material 3's own focus state is a ~10% lighter tint, which on a TV across a room is close
 * to invisible: users could not tell which control they were on. This draws one bright ring
 * per window that slides from control to control as focus moves, and grows the focused
 * control slightly, without each layout having to opt in.
 *
 * The ring lives in the window's own overlay rather than in each control. Drawn inside a
 * control it was clipped: dialog buttons clip to their pill shape and cut it away entirely,
 * and cards cut it off at their edges.
 *
 * It also changes how sliders take the D-pad. A focused Material Slider swallows LEFT/RIGHT,
 * so moving sideways past one silently changed its value. Here a slider only moves focus
 * until it is "engaged": press OK to start adjusting (the ring turns white), LEFT/RIGHT then
 * change the value, and OK, BACK, UP or DOWN finish.
 *
 * All of this only applies when the window is not in touch mode, so phones are unaffected.
 */
object TvFocus {

    private const val ANIM_MS = 160L
    private const val RING_DP = 3f
    private const val GAP_DP = 4f
    private const val GROW_DP = 6f
    private const val MAX_SCALE = 1.06f
    private const val LIFT_DP = 6f

    private var sliderHintShown = false

    /** Install on an activity's window. Call after setContentView. */
    fun install(activity: Activity) = install(activity.window)

    /** Install on a dialog. Call after show(), when its window has content. */
    fun install(dialog: AlertDialog): AlertDialog {
        dialog.window?.let { install(it) }
        return dialog
    }

    fun install(window: Window) {
        val decor = window.decorView
        if (decor.getTag(R.id.tv_focus_installed) == true) return
        decor.setTag(R.id.tv_focus_installed, true)

        val layer = Layer(decor)
        decor.setTag(R.id.tv_focus_ring, layer)
        decor.overlay.add(layer)
        decor.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> layer.setBounds(0, 0, v.width, v.height) }
        layer.setBounds(0, 0, decor.width, decor.height)
        decor.viewTreeObserver.addOnPreDrawListener(ViewTreeObserver.OnPreDrawListener {
            layer.track()
            true
        })

        decor.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            old?.let { blur(it) }
            if (new == null || new.isInTouchMode) {
                layer.hide()
                return@addOnGlobalFocusChangeListener
            }
            if (wantsRing(new)) {
                focus(new, layer)
            } else if (new is ViewGroup) {
                // A container took focus (a dialog's button bar does). Nothing visible
                // would be selected, so hand focus to the first thing in it you can press.
                (firstActionable(new) ?: new.focusSearch(View.FOCUS_DOWN))?.requestFocus()
            }
        }
        // Sliders can be created later (dialogs, list rows), so attach whenever the
        // hierarchy changes rather than once.
        decor.viewTreeObserver.addOnGlobalLayoutListener { attachSliders(decor) }
        decor.post {
            attachSliders(decor)
            decor.findFocus()?.let { if (!it.isInTouchMode && wantsRing(it)) focus(it, layer) }
        }
    }

    // ── Focus ring ──────────────────────────────────────────────────────────

    /**
     * Rings go on things the user can act on. Containers that merely hold focus (a dialog's
     * button bar, a scroll view) would otherwise get a ring around a whole panel, which
     * reads as "this entire area is selected".
     */
    private fun wantsRing(view: View): Boolean =
        view.isClickable || view is Slider || view is android.widget.EditText ||
            view is android.widget.CompoundButton || view !is ViewGroup

    private fun firstActionable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable && wantsRing(child)) return child
            if (child is ViewGroup) firstActionable(child)?.let { return it }
        }
        return null
    }

    private fun focus(view: View, layer: Layer) {
        unclipAncestors(view)
        layer.moveTo(view)
        if (view is Slider) maybeHint(view)

        val d = view.resources.displayMetrics.density
        val w = view.width.coerceAtLeast(1)
        val scale = (1f + (GROW_DP * d * 2) / w).coerceAtMost(MAX_SCALE)
        view.animate()
            .scaleX(scale).scaleY(scale)
            .translationZ(LIFT_DP * d)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Let the slightly enlarged control draw past its parents' edges instead of being cut
     * off on one side. Stops at anything that scrolls, where unclipped children would paint
     * outside the viewport.
     */
    private fun unclipAncestors(view: View) {
        var p = view.parent
        var hops = 0
        while (p is ViewGroup && hops < 4) {
            if (p is android.widget.ScrollView || p is androidx.core.widget.NestedScrollView ||
                p is androidx.recyclerview.widget.RecyclerView || p is android.widget.HorizontalScrollView
            ) return
            p.clipChildren = false
            p.clipToPadding = false
            p = p.parent
            hops++
        }
    }

    private fun blur(view: View) {
        if (view is Slider) setEngaged(view, false)
        view.animate()
            .scaleX(1f).scaleY(1f)
            .translationZ(0f)
            .setDuration(ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun layerOf(view: View) = view.rootView.getTag(R.id.tv_focus_ring) as? Layer

    /**
     * The window's single focus ring. Follows its target every frame (so it tracks the grow
     * animation and scrolling) and glides from the previous target to the new one.
     */
    private class Layer(decor: View) : Drawable() {
        private val density = decor.resources.displayMetrics.density
        private val focusColor = decor.context.getColor(R.color.nv_green_bright)
        private val engagedColor = 0xFFFFFFFF.toInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density * 3.5f
        }

        private var target: View? = null
        private val from = RectF()
        private val to = RectF()
        private val shown = RectF()
        private var fromRadius = 0f
        private var toRadius = 0f
        private var radius = 0f
        private var progress = 1f
        private var level = 0
        private val loc = IntArray(2)
        private var moveAnim: ValueAnimator? = null
        private var fadeAnim: ValueAnimator? = null

        var engaged = false
            set(value) { field = value; invalidateSelf() }

        fun moveTo(view: View) {
            target = view
            engaged = isEngaged(view)
            measure(view, to)
            toRadius = radiusOf(view)
            if (level == 0) {
                // Appearing from nothing: start in place rather than sliding in.
                from.set(to); shown.set(to); fromRadius = toRadius; radius = toRadius
            } else {
                from.set(shown); fromRadius = radius
            }
            moveAnim?.cancel()
            progress = 0f
            moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ANIM_MS
                interpolator = DecelerateInterpolator(1.6f)
                addUpdateListener { progress = it.animatedValue as Float; track() }
                start()
            }
            fadeTo(255)
        }

        fun hide() {
            target = null
            fadeTo(0)
        }

        /** Recompute where the ring belongs; cheap, and only invalidates on change. */
        fun track() {
            val t = target ?: return
            if (!t.isAttachedToWindow || !t.isShown) { hide(); return }
            measure(t, to)
            toRadius = radiusOf(t)
            val nl = from.left + (to.left - from.left) * progress
            val nt = from.top + (to.top - from.top) * progress
            val nr = from.right + (to.right - from.right) * progress
            val nb = from.bottom + (to.bottom - from.bottom) * progress
            val nRad = fromRadius + (toRadius - fromRadius) * progress
            if (nl != shown.left || nt != shown.top || nr != shown.right || nb != shown.bottom || nRad != radius) {
                shown.set(nl, nt, nr, nb)
                radius = nRad
                invalidateSelf()
            }
        }

        private fun measure(view: View, out: RectF) {
            view.getLocationInWindow(loc)
            val sx = view.scaleX; val sy = view.scaleY
            var top = loc[1].toFloat()
            var bottom = top + view.height * sy
            if (view is MaterialButton) {
                // The visible pill sits inside vertical insets; hug the pill, not the bounds.
                top += view.insetTop * sy
                bottom -= view.insetBottom * sy
            }
            val gap = GAP_DP * density
            out.set(loc[0] - gap, top - gap, loc[0] + view.width * sx + gap, bottom + gap)
        }

        private fun radiusOf(view: View): Float {
            val gap = GAP_DP * density
            val h = view.height * view.scaleY
            val fallback = minOf(16f * density, h / 2f)
            val r = when (view) {
                // Buttons inside a toggle group report 0 (their inner corners are square).
                is MaterialButton -> view.cornerRadius.toFloat().takeIf { it > 0f } ?: fallback
                is MaterialCardView -> view.radius.takeIf { it > 0f } ?: fallback
                else -> fallback
            }
            return r * view.scaleX + gap
        }

        private fun fadeTo(target: Int) {
            fadeAnim?.cancel()
            fadeAnim = ValueAnimator.ofInt(level, target).apply {
                duration = ANIM_MS
                addUpdateListener { level = it.animatedValue as Int; invalidateSelf() }
                start()
            }
        }

        override fun draw(canvas: Canvas) {
            if (level == 0 || shown.isEmpty) return
            val color = if (engaged) engagedColor else focusColor
            glow.color = color
            glow.alpha = level * 45 / 255
            canvas.drawRoundRect(shown, radius, radius, glow)
            paint.color = color
            paint.alpha = level
            canvas.drawRoundRect(shown, radius, radius, paint)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    // ── Sliders: press OK to adjust ─────────────────────────────────────────

    private fun attachSliders(root: View) {
        if (root is Slider) attachSlider(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) attachSliders(root.getChildAt(i))
    }

    private fun isEngaged(view: View) = view.getTag(R.id.tv_slider_engaged) == true

    private fun setEngaged(slider: Slider, engaged: Boolean) {
        slider.setTag(R.id.tv_slider_engaged, engaged)
        layerOf(slider)?.let { if (slider.isFocused) it.engaged = engaged }
    }

    private fun attachSlider(slider: Slider) {
        if (slider.getTag(R.id.tv_slider_attached) == true) return
        slider.setTag(R.id.tv_slider_attached, true)

        slider.setOnKeyListener { v, keyCode, event ->
            if (v.isInTouchMode) return@setOnKeyListener false
            val engaged = isEngaged(v)
            val isOk = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
            val isSide = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

            when {
                isOk -> {
                    if (event.action == KeyEvent.ACTION_UP) setEngaged(slider, !engaged)
                    true
                }
                engaged && (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) -> {
                    if (event.action == KeyEvent.ACTION_UP) setEngaged(slider, false)
                    true
                }
                engaged && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) -> {
                    setEngaged(slider, false)
                    false // let focus move as usual
                }
                !engaged && isSide -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        val dir = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT
                        v.focusSearch(dir)?.requestFocus(dir)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun maybeHint(v: View) {
        if (sliderHintShown) return
        sliderHintShown = true
        Toast.makeText(v.context, R.string.tv_slider_hint, Toast.LENGTH_SHORT).show()
    }
}
