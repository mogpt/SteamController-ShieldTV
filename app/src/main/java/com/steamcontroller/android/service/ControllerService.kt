package com.steamcontroller.android.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.bt.BluetoothHidManager
import com.steamcontroller.android.input.GamepadMapper
import com.steamcontroller.android.input.ShizukuInputInjector
import com.steamcontroller.android.parser.Buttons
import com.steamcontroller.android.parser.SteamControllerState
import com.steamcontroller.android.parser.SteamReportParser
import com.steamcontroller.android.uinput.UInputGamepad
import com.steamcontroller.android.usb.HidReportReader
import com.steamcontroller.android.usb.SteamHidProtocol
import com.steamcontroller.android.usb.UsbConnectionManager
import com.steamcontroller.android.service.UsageStatsHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ControllerService : Service() {

    enum class InjectionMode { NONE, UINPUT, SHIZUKU_INJECT }

    companion object {
        private const val TAG = "ControllerService"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "steam_controller"
        const val EXTRA_DEVICE = "usb_device"
        const val ACTION_STOP = "com.steamcontroller.android.STOP"
        const val ACTION_NEXT_PROFILE = "com.steamcontroller.android.NEXT_PROFILE"
        const val ACTION_TEST_RUMBLE = "com.steamcontroller.android.TEST_RUMBLE"
        private const val TEST_RUMBLE_DURATION_MS = 5000L

        // Observed by DebugActivity / MainActivity for live display
        private val _stateFlow = MutableStateFlow<SteamControllerState?>(null)
        val stateFlow: StateFlow<SteamControllerState?> = _stateFlow.asStateFlow()

        private val _rawReportFlow = MutableStateFlow<ByteArray?>(null)
        val rawReportFlow: StateFlow<ByteArray?> = _rawReportFlow.asStateFlow()

        private val _modeFlow = MutableStateFlow(InjectionMode.NONE)
        val modeFlow: StateFlow<InjectionMode> = _modeFlow.asStateFlow()

        // Emits the active emulated profile id whenever it changes (start, cycle from notif, etc.)
        private val _profileFlow = MutableStateFlow<Int?>(null)
        val profileFlow: StateFlow<Int?> = _profileFlow.asStateFlow()

        // Controller battery as 0..100, or null if unknown. Updated when a HID report carries it.
        private val _batteryFlow = MutableStateFlow<Int?>(null)
        val batteryFlow: StateFlow<Int?> = _batteryFlow.asStateFlow()

        // True HID report rate in Hz, recomputed once a second.
        //
        // This has to be measured here rather than in DebugActivity: the UI-facing flows are
        // deliberately throttled, so counting their emissions measures the throttle, not the
        // controller. Reading ~30Hz over Bluetooth and concluding the link was slow would be
        // an easy and completely wrong diagnosis.
        private val _hidRateFlow = MutableStateFlow(0)
        val hidRateFlow: StateFlow<Int> = _hidRateFlow.asStateFlow()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var usbManager: UsbConnectionManager
    private lateinit var btManager: BluetoothHidManager
    private val legacyInjector = ShizukuInputInjector()
    private lateinit var uinput: UInputGamepad
    private var mode: InjectionMode = InjectionMode.NONE
    private var reader: HidReportReader? = null
    private var heartbeatJob: Job? = null

    // Bits whose changes must pass through the debounce mechanism before being injected.
    // Mechanical switches only — the SC2026's grip squeeze sensors (GRIP_LT/RT), trackpad
    // touch flags (TP_*) and stick touch flags (LS_TOUCH/RS_TOUCH) are capacitive and
    // inherently noisy when the controller is held; if those are mapped, the debounce
    // counter will keep resetting and the mapping will misbehave (known limitation).
    // L4/L5/R4/R5 ARE included — they are real mechanical back-paddle switches.
    private val INJECTABLE_MASK =
        Buttons.A or Buttons.B or Buttons.X or Buttons.Y or
        Buttons.LB or Buttons.RB or
        Buttons.LT_FULL or Buttons.RT_FULL or
        Buttons.MENU or Buttons.VIEW or Buttons.STEAM or Buttons.QUICK_ACCESS or
        Buttons.LS or Buttons.RS or
        Buttons.L4 or Buttons.L5 or Buttons.R4 or Buttons.R5 or
        Buttons.DPAD_UP or Buttons.DPAD_DOWN or Buttons.DPAD_LEFT or Buttons.DPAD_RIGHT

    // Debounce window, in milliseconds rather than frames. A frame count means a different
    // amount of real time per transport (USB ~333Hz, BLE ~60-100Hz), so the old fixed
    // 3-frame rule was ~9ms over USB but could exceed 30ms over Bluetooth — perceptible.
    // A time window gives identical, bounded latency on both links.
    private val DEBOUNCE_MS = 12L

    // Display-only flow throttle — see onHidFrame.
    private val UI_FLOW_INTERVAL_MS = 33L
    private var lastUiEmitMs = 0L

    /** Upper bound on how long onDestroy will wait for the Shizuku user service teardown. */
    private val UNBIND_TIMEOUT_MS = 2000L

    /**
     * Held for as long as the service runs.
     *
     * A foreground service keeps the process from being killed, but it does NOT keep the CPU
     * out of suspend. While the SHIELD is idle the kernel can suspend between wakeups, and
     * the BLE callbacks that feed this service stop being serviced promptly — the controller
     * appears to "disconnect for no reason" even though nothing was killed. A partial wake
     * lock keeps the CPU running so reports keep flowing with the screen off or another app
     * in front. It costs power, which is the right trade for a mains-powered TV box, and it
     * is only held while the user has explicitly started the service.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(android.os.PowerManager::class.java)
            wakeLock = pm?.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "SteamController::controller"
            )?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Partial wake lock acquired")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not acquire wake lock: ${t.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            Log.i(TAG, "Wake lock released")
        } catch (t: Throwable) {
            Log.w(TAG, "Could not release wake lock: ${t.message}")
        } finally {
            wakeLock = null
        }
    }
    private var confirmedState: SteamControllerState? = null
    private var pendingButtons = 0
    private var pendingSinceMs = 0L

    // isMouseMode is read on every HID frame (up to 333/s). Reading it through Prefs each
    // time meant a synchronized SharedPreferences lookup plus a GamepadProfile.values()
    // array allocation per frame. Cached here and refreshed only when the profile changes.
    @Volatile private var isMouseModeCached = false

    override fun onCreate() {
        super.onCreate()
        usbManager = UsbConnectionManager(this)
        btManager = BluetoothHidManager(this)
        uinput = UInputGamepad(this, Prefs.getProfile(this))
        uinput.onRumble = { strong, weak -> forwardRumble(strong, weak) }
        createNotificationChannel()
        val startProfile = Prefs.getProfile(this)
        isMouseModeCached = startProfile.isMouseMode
        _profileFlow.value = startProfile.id

        // Refresh the foreground notification whenever the controller's battery level changes.
        // StateFlow only emits on actual value changes, so this triggers ~once per percent dropped.
        scope.launch {
            _batteryFlow.collect { refreshNotification() }
        }

        startForegroundAppMonitor()
    }

    // ─── Foreground-app auto-switch (V1.2 Phase 2b) ────────────────────────────
    private var foregroundAppMonitorJob: Job? = null
    private var lastForegroundPackage: String? = null

    /**
     * Poll UsageStatsManager every 1.5s for the focused app. On change, look up
     * a matching named-profile binding and live-switch the gamepad profile.
     * Silently inert if the user hasn't granted PACKAGE_USAGE_STATS.
     */
    private fun startForegroundAppMonitor() {
        foregroundAppMonitorJob?.cancel()
        foregroundAppMonitorJob = scope.launch {
            while (isActive) {
                try { tickForegroundAppMonitor() } catch (t: Throwable) {
                    Log.w(TAG, "Foreground monitor tick failed: ${t.message}")
                }
                delay(1500)
            }
        }
    }

    private var loggedNoUsagePerm = false

    private fun tickForegroundAppMonitor() {
        if (!UsageStatsHelper.hasPermission(this)) {
            if (!loggedNoUsagePerm) {
                Log.w(TAG, "Auto-switch inert: PACKAGE_USAGE_STATS not granted")
                loggedNoUsagePerm = true
            }
            return
        }
        loggedNoUsagePerm = false

        val current = UsageStatsHelper.getCurrentForegroundApp(this) ?: return
        if (current == lastForegroundPackage) return
        Log.v(TAG, "Foreground changed: $lastForegroundPackage → $current")
        lastForegroundPackage = current
        // Ignore self — opening our own UI shouldn't trigger anything.
        if (current == packageName) return

        val bound = Prefs.listNamedProfiles(this)
            .firstOrNull { current in it.boundPackages }
        if (bound == null) {
            Log.v(TAG, "  no profile bound to $current")
            return
        }

        // Gate: skip only if BOTH the active named-profile id AND the live emulated
        // gamepad already match the binding. Without the live-profile check we'd skip
        // when the user has manually picked a different gamepad variant via the radios
        // (which doesn't clear activeNamedProfileId).
        val liveProfileMatches = Prefs.getProfile(this).id == bound.profileId
        val activeMatches = Prefs.getActiveNamedProfileId(this) == bound.id
        if (activeMatches && liveProfileMatches) {
            Log.v(TAG, "  '${bound.name}' already applied — skipping")
            return
        }

        Log.i(TAG, "Auto-switch → '${bound.name}' (foreground=$current, activeMatches=$activeMatches, liveMatches=$liveProfileMatches)")
        Prefs.applyNamedProfile(this, bound)
        announceProfileLoaded(bound.name)

        // Live-swap the gamepad profile (skip if not in uinput mode).
        if (mode == InjectionMode.UINPUT && !profileSwitchInFlight) {
            profileSwitchInFlight = true
            scope.launch {
                try {
                    val gp = com.steamcontroller.android.uinput.GamepadProfile.fromId(bound.profileId)
                    uinput.switchProfile(gp)
                    isMouseModeCached = gp.isMouseMode
                    _profileFlow.value = bound.profileId
                    refreshNotification()
                } finally {
                    profileSwitchInFlight = false
                }
            }
        } else {
            isMouseModeCached =
                com.steamcontroller.android.uinput.GamepadProfile.fromId(bound.profileId).isMouseMode
            _profileFlow.value = bound.profileId
            refreshNotification()
        }
    }

    /**
     * Surface the auto-switch to the user via:
     *  1. A LENGTH_LONG Toast on the main thread (cheapest signal, shows over the
     *     launching app — might be missed if the user is head-down, hence #2).
     *  2. The foreground-service notification text gets the profile name appended
     *     (persistent until the next swap), so the user can always pull the shade
     *     to confirm which preset is live.
     */
    private fun announceProfileLoaded(profileName: String) {
        Log.i(TAG, "announceProfileLoaded: $profileName")
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            android.widget.Toast.makeText(
                applicationContext,
                "Game Profile loaded: $profileName",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
        // Notification refresh happens via refreshNotification() in the caller
        // after _profileFlow.value is updated.
    }

    private var lastRumbleStrong = -1
    private var lastRumbleWeak   = -1
    private var lastRumbleSentAt = 0L

    /**
     * Manual rumble test: pulse both motors at full strength for TEST_RUMBLE_DURATION_MS,
     * then stop. Triggered by the "Test rumble" button in CalibrationActivity.
     */
    private fun testRumble() {
        Log.i(TAG, "Test rumble requested")
        // Bypass the throttle by resetting last-sent timestamps
        lastRumbleSentAt = 0
        forwardRumble(0xFFFF, 0xFFFF)
        scope.launch {
            delay(TEST_RUMBLE_DURATION_MS)
            lastRumbleSentAt = 0
            forwardRumble(0, 0)
        }
    }

    /**
     * Called when a game triggers a rumble effect on the virtual gamepad.
     * Magnitudes are 0..65535. Forwarded to the controller via the active transport.
     *
     * Throttled: we re-send at most every 50ms if the magnitudes change, or every
     * 200ms if they're the same (keep-alive for long-lasting effects).
     */
    private fun forwardRumble(strong: Int, weak: Int) {
        // Apply user-configured intensity (0..100% of game-requested magnitude)
        val intensity = Prefs.getRumbleIntensity(this)
        val scaledStrong = (strong * intensity / 100).coerceIn(0, 0xFFFF)
        val scaledWeak   = (weak   * intensity / 100).coerceIn(0, 0xFFFF)

        val now = System.currentTimeMillis()
        val changed = (scaledStrong != lastRumbleStrong || scaledWeak != lastRumbleWeak)
        val tooSoon = (now - lastRumbleSentAt) < (if (changed) 50 else 200)
        if (tooSoon) return
        lastRumbleStrong = scaledStrong
        lastRumbleWeak   = scaledWeak
        lastRumbleSentAt = now

        Log.v(TAG, "Rumble → controller: strong=$scaledStrong weak=$scaledWeak (intensity=$intensity%)")
        when (Prefs.getTransport(this)) {
            Transport.BLUETOOTH -> btManager.sendRumble(scaledStrong, scaledWeak)
            Transport.USB       -> {
                // USB rumble = feature report via controlTransfer. Not implemented yet —
                // requires identifying the exact SC2026 feature report ID (likely 0x8F
                // per hid-steam.c) and payload format. Same byte structure as BT.
                Log.v(TAG, "USB rumble not implemented yet")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_NEXT_PROFILE) {
            cycleProfile()
            return START_STICKY
        }

        if (intent?.action == ACTION_TEST_RUMBLE) {
            testRumble()
            return START_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        acquireWakeLock()

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DEVICE)
        }

        scope.launch { initialize(device) }
        return START_STICKY
    }

    private suspend fun initialize(device: UsbDevice?) {
        val transport = Prefs.getTransport(this@ControllerService)
        Log.i(TAG, "Initializing with transport=$transport")
        chooseInjectionMode()

        val ok = when (transport) {
            Transport.USB       -> initUsb(device)
            Transport.BLUETOOTH -> initBluetooth()
        }
        if (!ok) {
            Log.e(TAG, "Transport init failed, stopping service")
            stopSelf()
            return
        }
        Log.i(TAG, "Controller service running, injection mode = $mode")
    }

    private suspend fun initUsb(device: UsbDevice?): Boolean {
        val dev = device ?: usbManager.findSteamController()
        if (dev == null) {
            Log.e(TAG, "No Steam Controller found over USB")
            return false
        }
        if (!usbManager.connect(dev)) {
            Log.e(TAG, "Failed to connect USB device")
            return false
        }
        val conn = usbManager.connection!!
        val ep   = usbManager.endpointIn!!

        SteamHidProtocol.disableLizardMode(conn)

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                SteamHidProtocol.heartbeat(conn)
            }
        }

        reader = HidReportReader(conn, ep,
            onReport = { state, raw -> onHidFrame(state, raw) },
            onError  = { msg -> Log.e(TAG, "USB read error: $msg") }
        )
        reader?.start(scope)
        return true
    }

    private fun initBluetooth(): Boolean {
        if (!btManager.isBluetoothAvailable) {
            Log.e(TAG, "Bluetooth disabled or unavailable")
            return false
        }
        val savedName = Prefs.getBluetoothName(this)
        val savedAddress = Prefs.getBluetoothAddress(this)
        if (savedName == null && savedAddress == null) {
            Log.e(TAG, "No paired Bluetooth Steam Controller selected")
            return false
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            return false
        }

        // Resolve by NAME against the current bond list, not by stored address.
        //
        // The SC2026 advertises over BLE using a random resolvable address, which rotates
        // (seen changing three times across one session, and again on every re-pair). A
        // stored address therefore goes stale and getRemoteDevice() hands back a device
        // that will never connect — the app reports itself configured while silently
        // failing, and the only cure was re-picking the controller in the dropdown by hand.
        // The friendly name embeds the controller's serial number, so it is stable and
        // unique unless the user renames the device.
        var device = if (savedName != null) {
            try {
                adapter.bondedDevices?.firstOrNull { bonded ->
                    (try { bonded.name } catch (_: SecurityException) { null }) == savedName
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Could not read bonded devices: ${t.message}"); null
            }
        } else null

        if (device != null) {
            if (device.address != savedAddress) {
                Log.i(TAG, "BLE address for '$savedName' rotated: $savedAddress -> ${device.address}")
                Prefs.setBluetoothAddress(this, device.address)
            }
        } else if (savedAddress != null) {
            // Name lookup failed (renamed, unbonded, or name unreadable) — fall back.
            Log.w(TAG, "No bonded device named '$savedName'; falling back to address $savedAddress")
            device = try {
                adapter.getRemoteDevice(savedAddress)
            } catch (t: Throwable) {
                Log.e(TAG, "Invalid BT address $savedAddress: ${t.message}")
                null
            }
        }

        if (device == null) {
            Log.e(TAG, "Could not resolve a Bluetooth device (name='$savedName', address=$savedAddress)")
            return false
        }

        btManager.connect(
            device,
            onReport = { raw ->
                val state = SteamReportParser.parse(raw) ?: SteamReportParser.parseRaw(raw)
                onHidFrame(state, raw)
            },
            onConnectionChange = { connected ->
                Log.i(TAG, "BT connection state: $connected")
            }
        )

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                btManager.sendHeartbeat()
            }
        }
        return true
    }

    private var rateWindowStartMs = 0L
    private var rateFrames = 0

    private fun onHidFrame(state: SteamControllerState, raw: ByteArray) {
        // True report rate, sampled over a 1s window.
        val tRate = android.os.SystemClock.uptimeMillis()
        if (rateWindowStartMs == 0L) rateWindowStartMs = tRate
        rateFrames++
        if (tRate - rateWindowStartMs >= 1000) {
            _hidRateFlow.value = (rateFrames * 1000L / (tRate - rateWindowStartMs)).toInt()
            rateWindowStartMs = tRate
            rateFrames = 0
        }

        // The UI-facing flows are display-only. Writing them on every HID frame pushed up to
        // 333 updates/s at MainActivity and DebugActivity, whose collectors run on the main
        // thread — enough to visibly hitch the UI on low-RAM devices while adding nothing a
        // human can see. Throttled to UI_FLOW_INTERVAL_MS (~30Hz); the injection path below
        // is untouched and still runs at full rate.
        val nowUi = android.os.SystemClock.uptimeMillis()
        if (nowUi - lastUiEmitMs >= UI_FLOW_INTERVAL_MS) {
            lastUiEmitMs = nowUi
            _stateFlow.value = state
            _rawReportFlow.value = raw
        }

        // Dedicated battery/charge report (id 0x43) — works on both USB and BT,
        // percent is already 0-100. Sole battery source: bytes 44-45 of the 0x45 state
        // report were assumed to be a static battery field but turned out to be live,
        // fast-changing data (empirically: flickers 0%/99% on USB), so that guess isn't used.
        SteamReportParser.parseBatteryStatus(raw)?.let { status ->
            if (_batteryFlow.value != status.percent) _batteryFlow.value = status.percent
        }

        if (raw.isNotEmpty() && (raw[0].toInt() and 0xFF) == 0x45) {
            handleState(state)
        }
    }

    // Try uinput first (real InputDevice, works in games). Fall back to inject if uinput is denied.
    private suspend fun chooseInjectionMode() {
        // 1. Try uinput via Shizuku UserService
        try {
            uinput.bind()
            for (attempt in 0 until 20) {
                if (uinput.isReady) break
                delay(150)
            }
            if (uinput.isReady) {
                setMode(InjectionMode.UINPUT)
                Log.i(TAG, "Using uinput virtual gamepad (${Prefs.getProfile(this@ControllerService).displayName})")
                return
            }
            Log.w(TAG, "uinput service did not become ready, falling back to inject")
            uinput.unbind()
        } catch (t: Throwable) {
            Log.w(TAG, "uinput bind failed: ${t.message}, falling back to inject")
        }

        // 2. Fallback: legacy injectInputEvent via Shizuku reflection
        if (legacyInjector.init()) {
            setMode(InjectionMode.SHIZUKU_INJECT)
            Log.i(TAG, "Using legacy Shizuku injectInputEvent (games may filter this)")
        } else {
            setMode(InjectionMode.NONE)
            Log.e(TAG, "No injection method available")
        }
    }

    private fun setMode(newMode: InjectionMode) {
        mode = newMode
        _modeFlow.value = newMode
        refreshNotification()
    }

    private fun refreshNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr?.notify(NOTIFICATION_ID, buildNotification())
    }

    /**
     * Triggered by the notification action: cycle to the next profile (Xbox360 → XboxOne → DS4 → DualSense → ...).
     * Only meaningful in uinput mode; in fallback inject mode the profile is ignored.
     */
    // Guards against re-entrant taps on the "Switch profile" notification action while
    // a previous switch is still tearing down / recreating uinput devices.
    @Volatile private var profileSwitchInFlight = false

    private fun cycleProfile() {
        if (profileSwitchInFlight) {
            Log.w(TAG, "Cycle profile ignored: a switch is already in progress")
            return
        }
        profileSwitchInFlight = true

        val profiles = com.steamcontroller.android.uinput.GamepadProfile.ALL
        val current = Prefs.getProfile(this)
        val next = profiles[(current.ordinal + 1) % profiles.size]
        Prefs.setProfile(this, next)
        Log.i(TAG, "Cycle profile: ${current.displayName} → ${next.displayName}")

        // Reset baseline state immediately so any buttons "held" during the swap
        // don't get injected via the now-defunct device.
        confirmedState = null
        pendingButtons = 0
        pendingSinceMs = 0L
        isMouseModeCached = next.isMouseMode

        // Run the actual device teardown/recreate off the service main thread —
        // it's a blocking binder + native ioctl pair that can take 100ms+.
        // Doing it on the main thread risks ANR / lost broadcast intents and was
        // the most likely cause of the "I can't change profile until I reboot" bug.
        scope.launch {
            try {
                if (mode == InjectionMode.UINPUT) {
                    val ok = uinput.switchProfile(next)
                    if (!ok) Log.w(TAG, "switchProfile failed; the gamepad may need a service restart")
                }
                _profileFlow.value = next.id
                refreshNotification()
            } finally {
                profileSwitchInFlight = false
            }
        }
    }

    private fun handleState(state: SteamControllerState) {
        if (mode == InjectionMode.NONE) return

        val now = android.os.SystemClock.uptimeMillis()

        // First frame = baseline
        if (confirmedState == null) {
            confirmedState = state
            pendingButtons = state.buttons and INJECTABLE_MASK
            pendingSinceMs = now
            return
        }

        // Button debounce: only inject once the mechanical bits have been stable for DEBOUNCE_MS
        val injectableBits = state.buttons and INJECTABLE_MASK
        val buttonsConfirmedThisFrame: Boolean
        // The state as it was BEFORE this frame's confirmation. SHIZUKU_INJECT needs it to
        // diff old vs new: confirmedState is overwritten below, so passing confirmedState to
        // GamepadMapper.buttons() after the fact compared `state` against itself and always
        // produced an empty key list — i.e. the fallback injection path never sent a single
        // button press, only axes.
        val previousConfirmed = confirmedState!!
        if (injectableBits == pendingButtons) {
            buttonsConfirmedThisFrame =
                (now - pendingSinceMs) >= DEBOUNCE_MS &&
                injectableBits != (previousConfirmed.buttons and INJECTABLE_MASK)
            if (buttonsConfirmedThisFrame) confirmedState = state
        } else {
            pendingButtons = injectableBits
            pendingSinceMs = now
            buttonsConfirmedThisFrame = false
        }

        when (mode) {
            InjectionMode.UINPUT -> {
                // Combine confirmed buttons with current raw axes — uinput frame is atomic.
                //
                // Only the mechanical bits in INJECTABLE_MASK are debounced. Every other bit
                // (capacitive: TP_LT/TP_RT touch, LS_TOUCH/RS_TOUCH, GRIP_*) must pass through
                // LIVE. Sending confirmedState.buttons wholesale froze those flags at whatever
                // they were the last time a mechanical button changed, which broke the sidecar
                // trackpad mouse: TP_RT stuck at 0 meant the cursor never moved, and stuck at 1
                // meant a phantom cursor drifting from stale pad coordinates.
                val frameToSend = if (isMouseModeCached) {
                    state
                } else {
                    val merged = (confirmedState!!.buttons and INJECTABLE_MASK) or
                                 (state.buttons and INJECTABLE_MASK.inv())
                    state.copy(buttons = merged)
                }
                uinput.pushFrame(frameToSend)
            }
            InjectionMode.SHIZUKU_INJECT -> {
                // Axes every frame for smoothness, with live-reloaded calibration
                legacyInjector.injectMotion(GamepadMapper.axes(
                    state,
                    Prefs.getLeftCalibration(this),
                    Prefs.getRightCalibration(this)
                ))
                // Buttons only on debounced change
                if (buttonsConfirmedThisFrame) {
                    GamepadMapper.buttons(state, previousConfirmed).forEach { (keyCode, down) ->
                        legacyInjector.injectKey(keyCode, down)
                    }
                }
            }
            InjectionMode.NONE -> { /* unreachable */ }
        }
    }

    override fun onDestroy() {
        // Ordering matters here, and the original order caused two distinct problems.
        //
        // 1. uinput.unbind() issues blocking binder calls into the Shizuku user service
        //    (restoring show_ime_with_hard_keyboard runs two `settings` shell commands, then
        //    destroy()). onDestroy runs on the MAIN thread, so doing that inline could stall
        //    the UI thread for hundreds of ms on every Stop — a visible freeze, and an ANR if
        //    the user service was slow or wedged. It now runs on a worker joined with a
        //    timeout: normally it completes, but it can never hang the main thread.
        //
        // 2. The USB read loop was still in bulkTransfer() when usbManager.disconnect() closed
        //    the connection underneath it. Stop the reader first, then tear the transport down.
        releaseWakeLock()
        reader?.stop()
        scope.cancel()

        val teardown = Thread {
            try { uinput.unbind() } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }

        usbManager.disconnect()
        try { btManager.disconnect() } catch (_: Throwable) {}
        try { teardown.join(UNBIND_TIMEOUT_MS) } catch (_: InterruptedException) {}
        _modeFlow.value = InjectionMode.NONE
        // Reset state + battery so MainActivity's "is the controller actually here?"
        // observer flips back to disconnected on stop.
        _stateFlow.value = null
        _batteryFlow.value = null
        _hidRateFlow.value = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows controller status and active emulation profile"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val profile = Prefs.getProfile(this)
        val battery = _batteryFlow.value

        val modeText = when (mode) {
            InjectionMode.UINPUT         -> getString(R.string.notif_mode_uinput, profile.displayName)
            InjectionMode.SHIZUKU_INJECT -> getString(R.string.notif_mode_inject)
            InjectionMode.NONE           -> getString(R.string.notif_starting)
        }
        val title = getString(R.string.notification_title)
        // Append the active named profile name so the user can pull the shade and
        // see which preset auto-switch loaded for them.
        val activeProfileName = Prefs.getActiveNamedProfileId(this)?.let { id ->
            Prefs.listNamedProfiles(this).firstOrNull { it.id == id }?.name
        }
        val baseLine = if (battery != null) "$modeText  •  🔋 $battery%" else modeText
        val text = if (activeProfileName != null) "$baseLine\n🎯 $activeProfileName" else baseLine

        // Tap on the notification → open MainActivity
        val openIntent = Intent(this, com.steamcontroller.android.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ControllerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
            getString(android.R.string.cancel),
            stopIntent
        ).build()

        // "Switch profile" action: cycles to the next emulated controller (only useful in uinput mode).
        val nextProfileIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ControllerService::class.java).apply { action = ACTION_NEXT_PROFILE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nextLabel = if (mode == InjectionMode.UINPUT) {
            val profiles = com.steamcontroller.android.uinput.GamepadProfile.ALL
            val next = profiles[(profile.ordinal + 1) % profiles.size]
            "→ ${next.displayName}"
        } else {
            "Switch profile"
        }
        val switchAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
            nextLabel,
            nextProfileIntent
        ).build()

        val icon = when (mode) {
            InjectionMode.UINPUT         -> android.R.drawable.ic_media_play
            InjectionMode.SHIZUKU_INJECT -> android.R.drawable.ic_media_play
            InjectionMode.NONE           -> android.R.drawable.stat_notify_sync
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(icon)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)

        // Only show the switch action when uinput is active — pointless when in fallback or starting
        if (mode == InjectionMode.UINPUT) {
            builder.addAction(switchAction)
        }
        builder.addAction(stopAction)

        return builder.build()
    }
}
