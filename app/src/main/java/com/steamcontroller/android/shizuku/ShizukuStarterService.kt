package com.steamcontroller.android.shizuku

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Presses Shizuku's "Start" button for the user, because on Android TV they cannot.
 *
 * WHY THIS EXISTS
 * ---------------
 * Shizuku's home screen shows a "Start via Wireless debugging" card holding three buttons:
 * "Step-by-step guide", "Pairing" and "Start". Those buttons are `focusable="true"` in the
 * view hierarchy, but the MaterialCardView wrapping them is itself focusable and clickable
 * and blocks descendant focus. Walking the screen with a D-pad therefore hops card → card
 * and never enters a card, so the "Start" button is unreachable with a TV remote. Verified
 * on a SHIELD TV 2019 (Android 11): eight consecutive KEYCODE_DPAD_DOWN events move focus
 * between CardViews only, and KEYCODE_DPAD_CENTER on the wireless-debugging card is a no-op.
 * The dialog Start then opens has the same defect — the discovered adb port sits on a button
 * inside a ScrollView that keeps the focus to itself. Until now the only way through either
 * was to plug a mouse into the TV.
 *
 * WHY AN ACCESSIBILITY SERVICE AND NOT SOMETHING SMALLER
 * ------------------------------------------------------
 * Shizuku 13.6.0's manifest exports only MainActivity, the permission-request activities,
 * two receivers and a provider. The component that actually performs the wireless-debugging
 * start — `moe.shizuku.manager.starter.StarterActivity` — is NOT exported, and neither is
 * `moe.shizuku.manager.adb.AdbPairingService`, so we cannot invoke either from our process.
 * `adb shell input tap` and the Shizuku user service are both circular: they need the very
 * shell privilege that Shizuku is not yet providing. An AccessibilityService is the only
 * remaining route that needs zero elevated privilege — it can locate a node in another app
 * and call ACTION_CLICK on it directly.
 *
 * HOW IT IS KEPT NARROW
 * ---------------------
 * Accessibility is a broad permission, so this service is scoped as tightly as the API
 * allows:
 *   - `android:packageNames="moe.shizuku.privileged.api"` in the service config means the
 *     framework only ever delivers us events from Shizuku. We are structurally blind to
 *     every other app on the device, including our own.
 *   - It is inert unless [arm] was called. Arming happens only from an explicit user tap on
 *     our "Start Shizuku service" button, and expires after [ARM_WINDOW_MS].
 *   - It performs at most the two clicks the start flow needs — "Start", then the
 *     discovered adb port — and disarms itself as soon as the second one lands.
 *   - It requests no `flagRetrieveInteractiveWindows`, no key-event filtering, no gestures.
 */
class ShizukuStarterService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        disarm()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        disarm()
        super.onDestroy()
    }

    override fun onInterrupt() { /* nothing long-running to interrupt */ }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isArmed()) return
        if (event?.packageName != SHIZUKU_PACKAGE) return

        // Shizuku's card list animates in and the status card re-renders a moment after the
        // activity is resumed, so the Start button is often not in the tree on the very first
        // event. Rather than click on whatever happens to be there, retry on a short schedule
        // and take the first attempt that finds a real match. Each new event coalesces into a
        // fresh burst, which is also how stage 2 (below) gets picked up.
        handler.removeCallbacksAndMessages(null)
        for (delay in ATTEMPT_DELAYS_MS) {
            handler.postDelayed({ if (isArmed()) attempt() }, delay)
        }
    }

    /**
     * One pass over whatever Shizuku currently has on screen.
     *
     * Starting Shizuku from the device is two clicks, not one, and *both* are unreachable
     * with a D-pad:
     *
     *   Stage 1 — "Start" in the "Start via Wireless debugging" card.
     *   Stage 2 — the discovered adb port in the "Searching for wireless debugging service"
     *             dialog that Start opens. That dialog puts the port on an AlertDialog
     *             neutral button inside a ScrollView, which swallows focus exactly the way
     *             the card does.
     *
     * Stage 2 is tried first because when that dialog is up it owns the active window.
     */
    private fun attempt() {
        val root = rootInActiveWindow ?: return
        try {
            if (!stage2Done) {
                findPortButton(root)?.let { node ->
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "stage 2: clicked wireless-debugging port '${node.text}': $clicked")
                    if (clicked) {
                        stage2Done = true
                        // Nothing further to do — Shizuku takes over from here.
                        disarm()
                    }
                    return
                }
            }
            if (!stage1Done) {
                findStartButton(root)?.let { node ->
                    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "stage 1: clicked Shizuku Start button: $clicked")
                    if (clicked) stage1Done = true
                    // Stay armed: the port dialog is what comes next.
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "click attempt failed", t)
        }
    }

    /**
     * Locate Shizuku's "Start" button (stage 1).
     *
     * The selector is the *framework* view id `android:id/button1`. Shizuku ships with
     * resource-name obfuscation (every `moe.shizuku...:id/...` reads back as
     * `0_resource_name_obfuscated`), but the three buttons in that card carry the platform's
     * own ids — button3 = "Step-by-step guide", button2 = "Pairing", button1 = "Start".
     * Those are stable across Shizuku versions and, unlike the visible label, identical in
     * every language.
     *
     * The CardView-ancestor requirement is not cosmetic. `android:id/button1` is also the
     * positive button of every AlertDialog Shizuku shows — in the port dialog it is
     * "Developer options" — so an id-only match would happily click the wrong thing. The
     * three wireless-debugging buttons live inside an `androidx.cardview.widget.CardView`;
     * dialog button bars never do.
     */
    private fun findStartButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val byId = root.findAccessibilityNodeInfosByViewId(ID_BUTTON1)
            ?.filter { it.isClickable && it.isEnabled && it.isVisibleToUser && hasCardAncestor(it) }
            ?: emptyList()

        // With more than one candidate, prefer the three-button signature (guide + pairing +
        // start), then the topmost — the wireless-debugging card sits above the
        // "connect to a computer" one.
        if (byId.isNotEmpty()) {
            return byId.firstOrNull { hasSiblingButtons(it) } ?: byId.first()
        }

        return findByLabel(root)
    }

    /**
     * Locate the discovered adb port in Shizuku's wireless-debugging search dialog (stage 2).
     *
     * Matching on "the label is nothing but a TCP port number" is deliberate: it is the one
     * thing in that dialog that cannot be confused with "Cancel" or "Developer options", and
     * it needs no translation. Anything that is not a plain number is left alone.
     */
    private fun findPortButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val buttons = ArrayList<AccessibilityNodeInfo>()
        for (id in DIALOG_BUTTON_IDS) {
            root.findAccessibilityNodeInfosByViewId(id)?.let { buttons.addAll(it) }
        }
        return buttons.firstOrNull { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser &&
                !hasCardAncestor(node) &&
                node.text?.toString()?.trim()?.toIntOrNull()?.let { it in 1..65535 } == true
        }
    }

    /** True when this node's parent also holds `android:id/button2` and `android:id/button3`. */
    private fun hasSiblingButtons(node: AccessibilityNodeInfo): Boolean {
        val parent = node.parent ?: return false
        var sawButton2 = false
        var sawButton3 = false
        for (i in 0 until parent.childCount) {
            when (parent.getChild(i)?.viewIdResourceName) {
                ID_BUTTON2 -> sawButton2 = true
                ID_BUTTON3 -> sawButton3 = true
            }
        }
        return sawButton2 && sawButton3
    }

    /** Walks up looking for a CardView, which is what separates a card from a dialog. */
    private fun hasCardAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.className?.toString()?.contains("CardView") == true) return true
            current = current.parent
            hops++
        }
        return false
    }

    /**
     * Last-resort label match for stage 1. Deliberately strict: only a real Button whose
     * whole label is "Start", inside a card. Shizuku's screen also carries the strings
     * "Start via Wireless debugging" and "Start by connecting to a computer", and a
     * substring match would happily click those.
     */
    private fun findByLabel(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = root.findAccessibilityNodeInfosByText(FALLBACK_LABEL) ?: return null
        return candidates.firstOrNull { node ->
            node.isClickable && node.isEnabled && node.isVisibleToUser &&
                node.className == "android.widget.Button" &&
                FALLBACK_LABEL.equals(node.text?.toString()?.trim(), ignoreCase = true) &&
                hasCardAncestor(node)
        }
    }

    companion object {
        private const val TAG = "ShizukuStarter"

        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        private const val ID_BUTTON1 = "android:id/button1"
        private const val ID_BUTTON2 = "android:id/button2"
        private const val ID_BUTTON3 = "android:id/button3"
        private val DIALOG_BUTTON_IDS = arrayOf(ID_BUTTON3, ID_BUTTON2, ID_BUTTON1)
        private const val FALLBACK_LABEL = "Start"

        /** Depth cap on the CardView ancestor walk; the real distance is 3–4 levels. */
        private const val MAX_ANCESTOR_HOPS = 12

        /**
         * How long an arm request stays live. Long enough to cover a cold start of the
         * Shizuku app on a slow TV plus the mDNS discovery that stage 2 waits on, short
         * enough that a user who changes their mind and wanders into Shizuku by hand later
         * does not get a surprise click.
         */
        private const val ARM_WINDOW_MS = 45_000L

        /**
         * Retry schedule after a Shizuku window event, in ms. The tail matters for stage 2:
         * the port dialog only fills in once mDNS discovery returns, which on a TV over
         * Wi-Fi is comfortably a second or two after the dialog itself appears.
         */
        private val ATTEMPT_DELAYS_MS = longArrayOf(300L, 900L, 1800L, 3000L, 5000L, 8000L)

        private val handler = Handler(Looper.getMainLooper())

        @Volatile private var instance: ShizukuStarterService? = null
        @Volatile private var armedUntil = 0L
        @Volatile private var stage1Done = false
        @Volatile private var stage2Done = false

        /** True once the user has enabled us in Settings and the framework has bound us. */
        fun isConnected(): Boolean = instance != null

        /**
         * Belt-and-braces check for the UI: `isConnected` only becomes true after the
         * framework binds us, which can lag a return from the Settings screen. Reading the
         * secure setting directly tells us what the user chose right now.
         */
        fun isEnabledInSettings(context: Context): Boolean {
            val flat = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            } catch (_: Throwable) {
                null
            } ?: return false

            val target = "${context.packageName}/${ShizukuStarterService::class.java.name}"
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(flat)
            for (entry in splitter) {
                if (entry.equals(target, ignoreCase = true)) return true
            }
            return false
        }

        /**
         * Allow one Start click and one port-selection click on Shizuku within the next
         * [ARM_WINDOW_MS]. Each stage fires at most once per arm.
         */
        fun arm() {
            armedUntil = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
            stage1Done = false
            stage2Done = false
            Log.i(TAG, "armed for ${ARM_WINDOW_MS}ms")
        }

        fun disarm() {
            armedUntil = 0L
        }

        private fun isArmed(): Boolean = SystemClock.elapsedRealtime() < armedUntil
    }
}
