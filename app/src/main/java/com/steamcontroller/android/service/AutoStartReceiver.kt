package com.steamcontroller.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.steamcontroller.android.Prefs
import com.steamcontroller.android.R
import com.steamcontroller.android.Transport
import com.steamcontroller.android.shizuku.LocalAdbStarter
import rikka.shizuku.Shizuku

/**
 * Starts [ControllerService] without the user having to open the app.
 *
 * Two triggers, each independently switchable in Settings:
 *
 *  1. BOOT_COMPLETED — the box came back up, put the controller back to work.
 *  2. Bluetooth ACL_CONNECTED — the user turned the controller on. This is the one that
 *     matters day to day: the SHIELD is usually already running, and "press the Steam
 *     button and start playing" is the behaviour a real gamepad has.
 *
 * There is no USB equivalent here because there already is one: MainActivity carries a
 * USB_DEVICE_ATTACHED intent-filter, and Android launches it on attach.
 */
class AutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoStartReceiver"

        /** Low-importance channel for the "auto-start couldn't run" advisory. */
        private const val ADVISORY_CHANNEL_ID = "steam_controller_autostart"
        private const val ADVISORY_NOTIFICATION_ID = 2

        /**
         * Suppress repeat advisories within this window.
         *
         * ACL_CONNECTED can fire several times in quick succession for one physical
         * power-on (the controller brings up more than one link, and Android re-broadcasts
         * on reconnect). Without a throttle, a Shizuku-not-running boot would post the same
         * notification over and over — precisely the spam this is meant to avoid.
         */
        private const val ADVISORY_COOLDOWN_MS = 60_000L

        @Volatile private var lastAdvisoryAtMs = 0L

        /**
         * Budget for starting Shizuku over local adb from a broadcast. A healthy start takes
         * about three seconds on the SHIELD; this stays inside the 10 s a receiver is allowed.
         */
        private const val ADB_START_BUDGET_MS = 6_000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON" -> onBoot(context)

            BluetoothDevice.ACTION_ACL_CONNECTED -> onAclConnected(context, intent)
        }
    }

    private fun onBoot(context: Context) {
        // A reboot is a fresh session: a Stop the user pressed before the last shutdown
        // shouldn't keep auto-start disarmed forever.
        Prefs.setUserStoppedService(context, false)

        if (!Prefs.getAutoStartOnBoot(context)) {
            Log.i(TAG, "Boot auto-start disabled by preference")
            // Still bring Shizuku up now, so that turning the controller on later starts
            // the service at once instead of paying for the Shizuku start then.
            if (Prefs.getAutoStartOnControllerConnect(context) && !isShizukuReady()) {
                inBackground { LocalAdbStarter.start(context.applicationContext, ADB_START_BUDGET_MS, interactive = false) }
            }
            return
        }
        Log.i(TAG, "BOOT_COMPLETED — attempting auto-start")
        tryStart(context, reason = "boot")
    }

    private fun onAclConnected(context: Context, intent: Intent) {
        if (!Prefs.getAutoStartOnControllerConnect(context)) return

        // Only meaningful when the user has the app configured for Bluetooth. Over USB
        // the ACL broadcast has nothing to do with our controller.
        if (Prefs.getTransport(context) != Transport.BLUETOOTH) return

        val device: BluetoothDevice? =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
        if (device == null) return
        if (!isConfiguredController(context, device)) return

        Log.i(TAG, "Configured controller connected — attempting auto-start")
        tryStart(context, reason = "controller connect")
    }

    /**
     * Identify the broadcast's device as the one the user picked.
     *
     * Matched by NAME first, exactly as ControllerService.initBluetooth resolves it: the
     * SC2026's BLE address is a random resolvable one that rotates between sessions, so an
     * address comparison alone would stop matching after a rotation and auto-start would
     * quietly never fire again. Address is kept only as a fallback for devices whose name
     * can't be read.
     */
    private fun isConfiguredController(context: Context, device: BluetoothDevice): Boolean {
        val savedName = Prefs.getBluetoothName(context)
        val savedAddress = Prefs.getBluetoothAddress(context)
        val name = try { device.name } catch (_: SecurityException) { null }

        if (savedName != null && name != null) return name == savedName
        if (savedAddress != null) return device.address == savedAddress
        return false
    }

    private fun tryStart(context: Context, reason: String) {
        if (Prefs.getUserStoppedService(context)) {
            Log.i(TAG, "Auto-start ($reason) skipped: user stopped the service deliberately")
            return
        }
        if (ControllerService.isRunning) {
            Log.i(TAG, "Auto-start ($reason) skipped: service already running")
            return
        }
        if (isShizukuReady()) {
            startService(context, reason)
            return
        }

        // Expected after every reboot: Shizuku does not survive one. Try to bring it back
        // through the device's own adbd before giving up — see LocalAdbStarter. That blocks
        // for a few seconds, so it runs off the main thread with the broadcast held open.
        val appContext = context.applicationContext
        inBackground {
            val result = LocalAdbStarter.start(appContext, ADB_START_BUDGET_MS, interactive = false)
            if (result == LocalAdbStarter.Result.STARTED && isShizukuReady()) {
                Log.i(TAG, "Auto-start ($reason): started Shizuku over local adb")
                startService(appContext, reason)
            } else {
                // Starting the service anyway would just park a useless foreground
                // notification and a wake lock with no uinput device behind it, so
                // don't — say so once instead.
                Log.w(TAG, "Auto-start ($reason) skipped: Shizuku is not ready ($result)")
                postShizukuAdvisory(appContext)
            }
        }
    }

    private fun startService(context: Context, reason: String) {
        try {
            context.startForegroundService(Intent(context, ControllerService::class.java))
            Log.i(TAG, "Auto-start ($reason): service start requested")
        } catch (t: Throwable) {
            // Android 12+ can refuse a background foreground-service start
            // (ForegroundServiceStartNotAllowedException). Both of our triggers are on the
            // platform's exemption list, but a vendor build could still say no — losing
            // auto-start is not a reason to crash the user's boot.
            Log.e(TAG, "Auto-start ($reason) failed: ${t.message}")
        }
    }

    /** Run [block] on a worker thread while keeping this broadcast (and the process) alive. */
    private fun inBackground(block: () -> Unit) {
        val pending = goAsync()
        Thread({
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "Background auto-start work failed", t)
            } finally {
                pending.finish()
            }
        }, "autostart-adb").start()
    }

    private fun isShizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        // checkSelfPermission throws if the binder died between the ping and the call.
        Log.v(TAG, "Shizuku probe failed: ${t.message}")
        false
    }

    /**
     * One quiet, dismissible notification explaining why nothing happened.
     *
     * The alternative — logging only — leaves the user staring at a controller that does
     * nothing after a reboot with no clue that Shizuku is the missing piece. Tapping it
     * opens the app, which already knows how to send them on to Shizuku.
     */
    private fun postShizukuAdvisory(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastAdvisoryAtMs < ADVISORY_COOLDOWN_MS) return
        lastAdvisoryAtMs = now

        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                ADVISORY_CHANNEL_ID,
                context.getString(R.string.autostart_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )

        val openPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, com.steamcontroller.android.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = context.getString(R.string.autostart_shizuku_advisory_text)
        val notification = Notification.Builder(context, ADVISORY_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.autostart_shizuku_advisory_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        try {
            mgr.notify(ADVISORY_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not post auto-start advisory: ${t.message}")
        }
    }
}
