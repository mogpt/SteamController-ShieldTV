package com.steamcontroller.android.bt

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SC2026 via Bluetooth LE — uses Valve's vendor GATT service (100f6c32-...).
 * The standard HID service 0x1812 is claimed by the OS and requires BLUETOOTH_PRIVILEGED.
 *
 * GATT operations are serialized via a small state machine:
 *   CONNECTED → request MTU → discover services → subscribe N chars one-by-one → send disable lizard → READY
 * Each step waits for its own callback before triggering the next. Duplicate callbacks
 * (Android Bluetooth stack sometimes fires onMtuChanged twice) are ignored.
 */
@SuppressLint("MissingPermission")
class BluetoothHidManager(private val context: Context) {

    companion object {
        private const val TAG = "BluetoothHidManager"

        val VALVE_SERVICE_UUID: UUID = UUID.fromString("100f6c32-1735-4313-b402-38567131e5f3")
        private const val VALVE_NOTIFY_LOW: Long  = 0x100f6c75L
        private const val VALVE_NOTIFY_HIGH: Long = 0x100f6c7aL
        private const val VALVE_WRITE_LOW: Long   = 0x100f6cb5L
        private const val VALVE_WRITE_HIGH: Long  = 0x100f6cbeL
        private const val BATTERY_CHAR_SHORT: Long = 0x100f6c78L

        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val NAME_HINTS = listOf("Steam Ctrl", "Steam Controller", "SteamController", "Valve")

        private val DISABLE_LIZARD = byteArrayOf(0x85.toByte())
        private const val DESIRED_MTU = 100
    }

    private enum class State { IDLE, CONNECTING, MTU_REQUESTED, DISCOVERING, SUBSCRIBING, READY }

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = btManager.adapter

    private var gatt: BluetoothGatt? = null
    private var featureWriteChar: BluetoothGattCharacteristic? = null
    private var batteryChar: BluetoothGattCharacteristic? = null

    private val pendingSubs = mutableListOf<BluetoothGattCharacteristic>()
    private var subsIndex = 0

    // The subscription chain advances only when onDescriptorWrite fires. Android's GATT
    // stack permits one outstanding operation at a time and silently drops a write issued
    // while another is in flight — writeDescriptor() still returns true, but no callback
    // ever arrives. Observed in the wild: a late second onMtuChanged landed exactly as the
    // first CCCD write went out, the callback was lost, and the chain stalled at index 0.
    // The controller then stays subscribed only to the first notify characteristic, which
    // carries 5-byte packets rather than the real state reports — the app reports the
    // controller as ready while no input ever arrives.
    private val subsHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var subsTimeout: Runnable? = null
    private var subsRetries = 0
    private val SUBSCRIBE_TIMEOUT_MS = 2000L
    // One retry only. Evidence from a real SC2026 on Android 11: the first CCCD write is
    // accepted and never completes, which wedges Android's single-outstanding-operation
    // GATT queue, so every later write returns false permanently. Retrying cannot clear a
    // wedged queue, and more attempts only add startup latency. Notifications still arrive
    // because setCharacteristicNotification() enables them locally and the CCCD is already
    // enabled on the controller from the pairing session.
    private val MAX_SUBSCRIBE_RETRIES = 1
    /** Backoff before re-issuing a descriptor write the stack rejected as busy. */
    private val SUBSCRIBE_BUSY_RETRY_MS = 150L

    // ── Battery polling ──────────────────────────────────────────────────────
    // The battery characteristic (100f6c78) never notifies on this hardware. Its CCCD
    // write is issued as part of the subscription chain, but the FIRST descriptor write of
    // that chain is accepted and never completes, wedging Android's one-operation-at-a-time
    // GATT queue, so every later write — including this CCCD — is rejected for the rest of
    // the connection. State reports keep arriving only because their CCCD was already
    // enabled on the controller during pairing and setCharacteristicNotification() enables
    // the local side without needing the queue.
    //
    // Reads are not affected by that wedge (it's writes that are stuck), so the fix is to
    // stop waiting for a notification that will never come and just ask. There already was
    // a one-shot seed read chained off the disable-lizard write; this turns it into a
    // repeating poll so the level tracks the battery draining over a long session.
    //
    // 30s is a deliberate compromise: a controller battery moves by ~1% every several
    // minutes, so this is already far more often than the value changes, while costing one
    // tiny GATT read against a link carrying ~81 state reports a second.
    private val BATTERY_POLL_INTERVAL_MS = 30_000L
    /** Re-try delay when the read is refused because another GATT op is in flight. */
    private val BATTERY_POLL_BUSY_RETRY_MS = 750L
    /** Grace period before the first read, so it doesn't race the disable-lizard write. */
    private val BATTERY_POLL_FIRST_DELAY_MS = 500L
    /** Consecutive refusals after which the fast retry stops (see the poll runnable). */
    private val MAX_BUSY_READ_RETRIES = 3
    private var consecutiveBusyReads = 0
    private var batteryPollRunnable: Runnable? = null

    /** Run subscribeNext after [delayMs], reusing the timeout slot so it is cancellable. */
    private fun scheduleSubs(g: BluetoothGatt, delayMs: Long) {
        cancelSubsTimeout()
        val r = Runnable { if (state == State.SUBSCRIBING) subscribeNext(g) }
        subsTimeout = r
        subsHandler.postDelayed(r, delayMs)
    }

    private fun cancelSubsTimeout() {
        subsTimeout?.let { subsHandler.removeCallbacks(it) }
        subsTimeout = null
    }

    /** Re-issue or skip a subscription whose descriptor-write callback never arrived. */
    private fun armSubsTimeout(g: BluetoothGatt) {
        cancelSubsTimeout()
        val r = Runnable {
            if (state != State.SUBSCRIBING) return@Runnable
            val stalled = subsIndex - 1
            if (subsRetries < MAX_SUBSCRIBE_RETRIES) {
                subsRetries++
                Log.w(TAG, "Subscription idx=$stalled timed out, retrying (attempt $subsRetries)")
                subsIndex = stalled          // rewind so the same characteristic is re-issued
            } else {
                Log.w(TAG, "Subscription idx=$stalled timed out after retry, skipping it")
                subsRetries = 0
            }
            subscribeNext(g)
        }
        subsTimeout = r
        subsHandler.postDelayed(r, SUBSCRIBE_TIMEOUT_MS)
    }

    /**
     * (Re)arm the repeating battery read. Idempotent — calling it again just resets the timer.
     *
     * Runs on the main looper alongside the subscription timeouts, so every GATT call this
     * class makes is issued from one thread and the ordering is easy to reason about.
     */
    private fun startBatteryPolling(g: BluetoothGatt) {
        stopBatteryPolling()
        val ch = batteryChar ?: run {
            Log.w(TAG, "No battery characteristic — battery will stay unknown")
            return
        }
        val r = object : Runnable {
            override fun run() {
                if (state != State.READY || gatt !== g) return
                val ok = try { g.readCharacteristic(ch) } catch (t: Throwable) {
                    Log.w(TAG, "Battery read threw: ${t.message}"); false
                }
                if (ok) {
                    consecutiveBusyReads = 0
                    subsHandler.postDelayed(this, BATTERY_POLL_INTERVAL_MS)
                    return
                }
                // A false usually means the stack had another operation outstanding — most
                // likely the 800ms heartbeat write — so a quick retry is worth it rather
                // than waiting out the whole interval and leaving the UI on "—".
                //
                // But "busy" can also be permanent. On a connection where the first CCCD
                // write is accepted and never completes, Android's GATT queue stays wedged
                // for the life of the link and EVERY operation is refused from then on
                // (confirmed on hardware: writeDescriptor, writeCharacteristic and this
                // read all return false together). Retrying at 750ms forever in that state
                // buys nothing and keeps poking a queue the heartbeat also needs, so give
                // up after a few tries and drop back to the normal interval.
                consecutiveBusyReads++
                if (consecutiveBusyReads == MAX_BUSY_READ_RETRIES) {
                    Log.w(TAG, "Battery read refused $consecutiveBusyReads times — " +
                        "GATT queue looks wedged; backing off to the normal interval")
                }
                subsHandler.postDelayed(
                    this,
                    if (consecutiveBusyReads < MAX_BUSY_READ_RETRIES) BATTERY_POLL_BUSY_RETRY_MS
                    else BATTERY_POLL_INTERVAL_MS,
                )
            }
        }
        batteryPollRunnable = r
        // Short initial delay rather than firing immediately: this is armed at the same
        // moment the disable-lizard write goes out, and a read issued on top of an
        // in-flight write is simply rejected.
        subsHandler.postDelayed(r, BATTERY_POLL_FIRST_DELAY_MS)
    }

    private fun stopBatteryPolling() {
        batteryPollRunnable?.let { subsHandler.removeCallbacks(it) }
        batteryPollRunnable = null
        consecutiveBusyReads = 0
    }

    @Volatile private var state: State = State.IDLE
    private val heartbeatBusy = AtomicBoolean(false)

    private var onReport: ((ByteArray) -> Unit)? = null
    private var onConnectionChange: ((Boolean) -> Unit)? = null

    val isBluetoothAvailable: Boolean get() = adapter != null && adapter.isEnabled

    fun listPairedSteamControllers(): List<BluetoothDevice> {
        val a = adapter ?: return emptyList()
        return try {
            a.bondedDevices.orEmpty().filter { dev ->
                val n = dev.name ?: return@filter false
                NAME_HINTS.any { hint -> n.contains(hint, ignoreCase = true) }
            }
        } catch (t: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT: ${t.message}")
            emptyList()
        }
    }

    fun connect(
        device: BluetoothDevice,
        onReport: (ByteArray) -> Unit,
        onConnectionChange: (Boolean) -> Unit
    ) {
        this.onReport = onReport
        this.onConnectionChange = onConnectionChange
        lastDevice = device
        intentionalDisconnect = false
        reconnectAttempts = 0
        cancelReconnect()
        openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice) {
        Log.i(TAG, "Connecting GATT to ${safeName(device)} (${device.address})")
        state = State.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ── Reconnect ────────────────────────────────────────────────────────────
    // A BLE link drops for all sorts of ordinary reasons: RF interference, the controller
    // idling out, the radio being starved while the box is busy. The manager previously
    // treated every drop as terminal — it closed the GATT, cleared its state and stopped —
    // so a momentary blip killed the controller until the user noticed and restarted the
    // service by hand. That is almost certainly the "disconnects for no apparent reason".
    private var lastDevice: BluetoothDevice? = null
    @Volatile private var intentionalDisconnect = false
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null

    private fun cancelReconnect() {
        reconnectRunnable?.let { subsHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Retry with exponential backoff, capped. Retries are unbounded on purpose: this only
     * runs while the foreground service is alive, which is an explicit user decision, and
     * giving up silently is exactly the behaviour being fixed.
     */
    private fun scheduleReconnect() {
        val device = lastDevice ?: return
        if (intentionalDisconnect) return
        cancelReconnect()
        val delay = (1000L shl reconnectAttempts.coerceAtMost(4)).coerceAtMost(15_000L)
        reconnectAttempts++
        Log.i(TAG, "Link lost — reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        val r = Runnable {
            if (intentionalDisconnect) return@Runnable
            openGatt(device)
        }
        reconnectRunnable = r
        subsHandler.postDelayed(r, delay)
    }

    fun disconnect() {
        intentionalDisconnect = true
        cancelReconnect()
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "disconnect: ${t.message}")
        } finally {
            gatt = null
            featureWriteChar = null
            batteryChar = null
            stopBatteryPolling()
            cancelSubsTimeout()
            pendingSubs.clear()
            subsIndex = 0
            state = State.IDLE
            onConnectionChange?.invoke(false)
        }
    }

    /**
     * Send a rumble command to the controller.
     * Magnitudes are Android FF values (0..65535) — strong = left motor, weak = right.
     *
     * REVERTED (2026-07-12): tried switching to OUT_HAPTIC_RUMBLE (0x80), the "modern"
     * continuous-haptic output report documented by github.com/ddeverill/SteamlessController
     * (SteamController.cpp SendRumbleOutput). On real SC2026 hardware over this BLE
     * characteristic it produced NO vibration at all, while this 0x8F pulse format DOES
     * (confirmed on hardware, feel not yet tuned). Likely explanation: 0x80/0x81/0x82 are
     * only valid as genuine USB HID *output* reports (a separate report channel from the
     * feature-report/raw-command scheme), which doesn't necessarily exist as a raw
     * writable command over this vendor GATT characteristic. 0x8F is the older SC1-style
     * direct command (hid-steam.c HAPTIC_PULSE) which the SC2026 firmware apparently still
     * honors here. Do not retry 0x80 without first confirming (via hardware log/sniff)
     * that a *different* characteristic in the 100f6cb5-be write range is meant for it.
     *
     * Payload format inspired by the Linux `hid-steam` driver (steam_haptic_pulse):
     *   byte 0: command id (0x8F = HAPTIC_PULSE)
     *   byte 1: pad id (0 = left, 1 = right)
     *   bytes 2-3: high period (u16 LE, microseconds — actuator ON time per cycle)
     *   bytes 4-5: low period  (u16 LE, microseconds — actuator OFF time per cycle)
     *   bytes 6-7: repeat count (u16 LE, 0xFFFF for continuous)
     *
     * Magnitude is encoded by the ratio high/low, repeated continuously (repeat=0xFFFF):
     *   magnitude 0xFFFF → high=2000us, low=1000us  (~66% duty, full strength)
     *   magnitude 0x0000 → explicit stop (repeat=0)
     */
    fun sendRumble(strong: Int, weak: Int) {
        if (state != State.READY) return
        val ch = featureWriteChar ?: return
        val g = gatt ?: return

        // Send left then right. WRITE_NO_RESPONSE so they don't queue up acks.
        for (payload in listOf(magnitudeToPayload(0, strong), magnitudeToPayload(1, weak))) {
            try {
                ch.value = payload
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                g.writeCharacteristic(ch)
            } catch (t: Throwable) {
                Log.w(TAG, "rumble write failed: ${t.message}")
            }
        }
    }

    private fun magnitudeToPayload(padId: Int, magnitude: Int): ByteArray {
        val mag = magnitude.coerceIn(0, 0xFFFF)
        if (mag == 0) {
            // BUG FIX (2026-07-12): this used to return null here and sendRumble() would
            // bail out without writing anything at all, on the assumption that "the
            // controller will stop on its own once the previous pulse's repeat count
            // expires". Confirmed wrong on hardware — a repeat=1 pulse from the branch
            // below just buzzes continuously, and the calibration screen's "Test rumble"
            // button had no way to ever stop it (magnitude 0 was silently skipped).
            // Explicit stop: repeat=0 to cancel any in-flight pulse train.
            return byteArrayOf(0x8F.toByte(), padId.toByte(), 0, 0, 0, 0, 0, 0)
        }
        // FIX (2026-07-12): the previous mapping kept lowPeriod fixed at 1000us and only
        // scaled highPeriod (100-2000us), which changes the pulse *frequency* across the
        // magnitude range (from ~1/(100+1000)=909Hz at low magnitude down to
        // 1/(2000+1000)=333Hz at max). LRA actuators (used in the Steam Controller's
        // haptics) only move significantly near their mechanical resonant frequency —
        // typically ~170-200Hz for this class of actuator — so most of that range was
        // driving well off-resonance, which loses amplitude independently of duty cycle.
        // Now the cycle period is held ~constant near resonance and only the duty cycle
        // (high/low ratio) varies with magnitude, which is the correct lever for perceived
        // intensity on a fixed-frequency drive. Exact resonant frequency is unconfirmed for
        // the SC2026 (no datasheet) — retune totalPeriodUs if this still feels off.
        //
        // ROUND 2 (2026-07-12): still too weak at 182Hz/88% max duty. Two changes together
        // (confounds the next test, but each is independently well-motivated and cheap to
        // back out if needed): nudged the frequency down to ~160Hz (period 6250us — some
        // LRAs used in game controllers resonate lower than 182Hz), and pushed max duty
        // from 88% to 97% (near-continuous drive at full magnitude — 0x8F's on/off pulse
        // model may just have a firmness ceiling below what a "big motor spins" rumble
        // feels like; 97% duty is close to that ceiling for this command).
        val totalPeriodUs = 6250  // ~160Hz
        val minDutyPct = 25
        val maxDutyPct = 97
        val dutyPct = minDutyPct + (mag * (maxDutyPct - minDutyPct) / 0xFFFF)
        val highPeriod = (totalPeriodUs * dutyPct / 100).coerceIn(1, totalPeriodUs - 1)
        val lowPeriod = totalPeriodUs - highPeriod
        // FIX (2026-07-12): was hardcoded to 1 — a single ~2-3ms pulse per send, repeated
        // only every 50-200ms by ControllerService.forwardRumble's throttle, so the motor
        // sat idle >95% of the time. Confirmed on hardware: felt too weak. 0xFFFF matches
        // our own documented protocol ("repeat count, 0xFFFF for continuous") — the pulse
        // now cycles continuously between sends instead of firing one brief blip.
        val repeat = 0xFFFF
        return byteArrayOf(
            0x8F.toByte(),                       // command id (HAPTIC_PULSE)
            padId.toByte(),
            (highPeriod and 0xFF).toByte(), (highPeriod shr 8 and 0xFF).toByte(),
            (lowPeriod and 0xFF).toByte(),  (lowPeriod shr 8 and 0xFF).toByte(),
            (repeat and 0xFF).toByte(),     (repeat shr 8 and 0xFF).toByte(),
        )
    }

    fun sendHeartbeat() {
        if (state != State.READY) return
        val g = gatt ?: return
        val ch = featureWriteChar ?: return
        if (!heartbeatBusy.compareAndSet(false, true)) return  // previous heartbeat not yet acked
        try {
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            if (!ok) {
                heartbeatBusy.set(false)
                Log.v(TAG, "heartbeat skipped: writeCharacteristic returned false")
            }
        } catch (t: Throwable) {
            heartbeatBusy.set(false)
            Log.w(TAG, "heartbeat write failed: ${t.message}")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    cancelReconnect()
                    onConnectionChange?.invoke(true)
                    // Request a tight connection interval (11.25–15ms) to minimize input latency.
                    // Default is ~50ms which is fine for sensors but laggy for gamepads.
                    val priOk = g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    Log.i(TAG, "requestConnectionPriority(HIGH) → $priOk")

                    if (state == State.CONNECTING) {
                        state = State.MTU_REQUESTED
                        val ok = g.requestMtu(DESIRED_MTU)
                        if (!ok) {
                            Log.w(TAG, "requestMtu($DESIRED_MTU) returned false, skipping to discover")
                            state = State.DISCOVERING
                            g.discoverServices()
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    onConnectionChange?.invoke(false)
                    try { g.close() } catch (_: Throwable) {}
                    gatt = null
                    featureWriteChar = null
                    stopBatteryPolling()
                    cancelSubsTimeout()
                    pendingSubs.clear()
                    subsIndex = 0
                    state = State.IDLE
                    scheduleReconnect()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: mtu=$mtu status=$status (state=$state)")
            // Guard: this callback is sometimes fired twice on Android. Only act once.
            if (state != State.MTU_REQUESTED) return
            state = State.DISCOVERING
            val ok = g.discoverServices()
            Log.i(TAG, "discoverServices → $ok")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.i(TAG, "onServicesDiscovered status=$status (state=$state)")
            if (state != State.DISCOVERING) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed")
                return
            }

            // Log everything once for empirical validation
            for (svc in g.services) {
                Log.i(TAG, "Service ${svc.uuid}")
                for (ch in svc.characteristics) {
                    Log.i(TAG, "  Char ${ch.uuid}  ${describeProps(ch.properties)}")
                }
            }

            val valve = g.getService(VALVE_SERVICE_UUID)
            if (valve == null) {
                Log.e(TAG, "Valve vendor service not found")
                return
            }

            pendingSubs.clear()
            featureWriteChar = null
            batteryChar = null
            for (ch in valve.characteristics) {
                val short = shortUuid(ch.uuid) ?: continue
                val canNotify = (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                val canWrite = (ch.properties and (
                        BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

                if (canNotify && short in VALVE_NOTIFY_LOW..VALVE_NOTIFY_HIGH) {
                    pendingSubs.add(ch)
                }
                if (short == BATTERY_CHAR_SHORT) {
                    batteryChar = ch
                }
                if (canWrite && short in VALVE_WRITE_LOW..VALVE_WRITE_HIGH && featureWriteChar == null) {
                    featureWriteChar = ch
                }
            }
            Log.i(TAG, "Found ${pendingSubs.size} notify chars; feature-write=${featureWriteChar?.uuid}")

            subsIndex = 0
            state = State.SUBSCRIBING
            subscribeNext(g)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.v(TAG, "onDescriptorWrite ${descriptor.uuid} status=$status (state=$state, idx=$subsIndex/${pendingSubs.size})")
            cancelSubsTimeout()
            subsRetries = 0
            if (state == State.SUBSCRIBING) subscribeNext(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            heartbeatBusy.set(false)
            if (status != 0) Log.w(TAG, "Write ${ch.uuid} failed: status=$status")
        }

        // Deprecated 3-arg overload (not the API 33+ byte[]-carrying one) — minSdk 26 means
        // the OS-side BluetoothGatt implementation on most devices only ever calls this one.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Battery read failed: status=$status")
                return
            }
            val data = ch.value ?: return
            if (shortUuid(ch.uuid) != BATTERY_CHAR_SHORT) return
            // byte[1] is the percent — the one byte of this characteristic that holds still
            // across samples taken seconds apart while the rest churn (counter/checksum).
            // Length is only sanity-checked, not pinned to the 14 bytes seen on the notify
            // path: a read can legitimately return a differently-sized payload, and
            // requiring an exact match would silently drop every reading.
            if (data.size < 2) {
                Log.w(TAG, "Battery read too short (${data.size}B)")
                return
            }
            if (batteryReadCount++ == 0) {
                Log.i(TAG, "First battery read (${data.size}B): " +
                    data.joinToString(" ") { "%02x".format(it) })
            } else {
                Log.v(TAG, "Battery read #$batteryReadCount: ${data[1].toInt() and 0xFF}%")
            }
            onReport?.invoke(byteArrayOf(0x43.toByte(), data[1], 0x00))
        }

        /** Log the first payload in full so an unexpected layout is diagnosable from logcat. */
        private var batteryReadCount = 0

        private var reportCounter = 0
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            val data = ch.value ?: return
            reportCounter++
            val short = shortUuid(ch.uuid)
            // BLE strips the HID Report ID prefix; prepend it back to reuse the USB parser.
            // State reports (>=40 bytes) get 0x45.
            //
            // Confirmed on hardware (2026-07-12 logcat capture) — two distinct short reports,
            // neither matching the 2-byte guess originally assumed here:
            //  - 100f6c78, 14 bytes, e.g. "01 5d ff 0f 2c 10 00 00 00 00 00 00 14 75": byte[1]
            //    (0x5d = 93) stays constant across samples seconds apart while every other
            //    byte fluctuates (counter/checksum) — almost certainly the battery percent.
            //    Re-prefixed as 0x43 to reuse SteamReportParser.parseBatteryStatus.
            //  - 100f6c79, 5 bytes, alternating "01 02 00 00 00" / "00 02 00 00 00" in
            //    lockstep with our 800ms heartbeat write — an ack/status ping-pong tied to
            //    writes, NOT battery. Forwarded as-is (unparsed).
            val toForward: ByteArray = when {
                data.size >= 40 -> {
                    val withId = ByteArray(data.size + 1)
                    withId[0] = 0x45
                    System.arraycopy(data, 0, withId, 1, data.size)
                    withId
                }
                short == BATTERY_CHAR_SHORT && data.size == 14 -> byteArrayOf(0x43.toByte(), data[1], 0x00)
                else -> {
                    Log.v(TAG, "Unrecognized short report (${data.size}B) from ${ch.uuid}: " +
                        data.joinToString(" ") { "%02x".format(it) })
                    data
                }
            }
            // Log only the first report and one every 1000 (Hz check) — way less spammy
            if (reportCounter == 1 || reportCounter % 1000 == 0) {
                Log.i(TAG, "Report #$reportCounter from ${ch.uuid}: ${data.size} bytes")
            }
            onReport?.invoke(toForward)
        }
    }

    private fun subscribeNext(g: BluetoothGatt) {
        cancelSubsTimeout()
        if (subsIndex >= pendingSubs.size) {
            // All subscriptions done — send disable lizard mode
            Log.i(TAG, "All ${pendingSubs.size} subscriptions complete, sending disable lizard")
            val ch = featureWriteChar
            state = State.READY
            if (ch == null) {
                Log.w(TAG, "No feature write char; skipping disable lizard")
                startBatteryPolling(g)
                return
            }
            ch.value = DISABLE_LIZARD
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = g.writeCharacteristic(ch)
            Log.i(TAG, "Disable lizard write: $ok")
            startBatteryPolling(g)
            return
        }

        val ch = pendingSubs[subsIndex++]
        val nOk = g.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd == null) {
            Log.w(TAG, "No CCCD on ${ch.uuid}, skipping")
            subscribeNext(g)
            return
        }
        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val wOk = g.writeDescriptor(cccd)
        Log.v(TAG, "Subscribe ${ch.uuid} idx=${subsIndex-1} setNotify=$nOk writeDesc=$wOk")
        if (!wOk) {
            // Rejection almost always means "another GATT operation is still in flight",
            // not "impossible". Cascading straight into the next characteristic — as this
            // did originally — then fails every remaining write for the same reason and
            // leaves the device subscribed to nothing, while the chain still reports
            // itself complete. Back off briefly and re-issue the SAME characteristic.
            if (subsRetries < MAX_SUBSCRIBE_RETRIES) {
                subsRetries++
                subsIndex--                       // rewind to retry this characteristic
                scheduleSubs(g, SUBSCRIBE_BUSY_RETRY_MS)
            } else {
                Log.w(TAG, "Giving up on ${ch.uuid} after $subsRetries busy retries")
                subsRetries = 0
                scheduleSubs(g, SUBSCRIBE_BUSY_RETRY_MS)
            }
        } else {
            // Accepted, but may still be silently dropped — see subsTimeout.
            armSubsTimeout(g)
        }
    }

    private fun shortUuid(uuid: UUID): Long? {
        val s = uuid.toString()
        if (!s.endsWith("-1735-4313-b402-38567131e5f3")) return null
        return try { java.lang.Long.parseLong(s.substring(0, 8), 16) } catch (_: Throwable) { null }
    }

    private fun describeProps(p: Int): String {
        val parts = mutableListOf<String>()
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0)              parts += "READ"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0)             parts += "WRITE"
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)            parts += "NOTIFY"
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0)          parts += "INDICATE"
        return parts.joinToString("|").ifEmpty { "—" }
    }

    private fun safeName(device: BluetoothDevice): String =
        try { device.name ?: "?" } catch (_: SecurityException) { "?" }
}
