package com.steamcontroller.android.uinput

import android.content.Context
import android.util.Log

// Bound by Shizuku.bindUserService() — this code runs in a separate process
// with shell UID (2000), which on most Android versions can open /dev/uinput.
//
// IMPORTANT: must have a no-arg constructor. Shizuku v13+ also tries the Context
// constructor first; either is acceptable.
class UInputService : IUInputService.Stub {

    companion object {
        private const val TAG = "UInputService"
        /** Own process name, as set by UserServiceArgs.processNameSuffix("uinput"). */
        private const val PROCESS_NAME = "com.steamcontroller.android:uinput"
        /**
         * If the client stops calling in for this long, assume it died and tear down.
         * ControllerService polls force feedback at 50Hz for as long as it is bound, so
         * silence is an unambiguous signal that nobody is driving this device any more.
         */
        private const val CLIENT_TIMEOUT_MS = 15_000L

        /** How long to keep the virtual devices alive waiting for a killed app to return. */
        private const val REVIVE_WINDOW_MS = 30_000L
        private const val REVIVE_RETRY_MS = 3_000L
        private const val APP_PACKAGE = "com.steamcontroller.android"
        const val ACTION_REVIVE = "com.steamcontroller.android.action.REVIVE"
    }

    /** Profile of the device currently registered with the kernel, or -1 for none. */
    @Volatile private var createdProfile = -1
    @Volatile private var clientToken: android.os.IBinder? = null
    @Volatile private var clientAlive = false

    /**
     * The app process was killed out from under us — on the 2 GB SHIELD that is the low
     * memory killer, several times an hour during a GeForce NOW session.
     *
     * Left alone, Android restarts the service on a back-off that grows with every kill
     * (11 s, 31 s, 70 s observed), and the old helper tore its devices down meanwhile, so
     * the game saw the controller vanish for over a minute. Instead:
     *
     *  - keep the virtual devices registered, so the game never sees a disconnect;
     *  - send one neutral frame, because the last frame before the kill may have had a
     *    stick deflected or a button down and nothing would ever release it;
     *  - ask the app to come back right now. We run as uid shell, so we can deliver a
     *    broadcast to its receiver, which starts the service without waiting for the
     *    back-off. The returning client rebinds to this same instance (daemon mode) and
     *    createGamepad() hands it the device that is already there.
     */
    private fun onClientDied(token: android.os.IBinder) {
        if (clientToken !== token) return
        clientAlive = false
        Log.w(TAG, "Client process died — holding devices and reviving the app")
        try {
            UInputNative.sendFrame(0, 0, 0, 0, 0, 0, 0, 0, 0)
            UInputNative.sendMouseFrame(0, 0, 0, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "neutral frame failed: ${t.message}")
        }
        Thread({
            val deadline = android.os.SystemClock.uptimeMillis() + REVIVE_WINDOW_MS
            while (!clientAlive && android.os.SystemClock.uptimeMillis() < deadline) {
                touch() // keep the idle watchdog out of this; the deadline here decides
                runShellCommand(arrayOf(
                    "am", "broadcast", "-a", ACTION_REVIVE,
                    "-n", "$APP_PACKAGE/.service.AutoStartReceiver",
                ))
                try { Thread.sleep(REVIVE_RETRY_MS) } catch (_: InterruptedException) { return@Thread }
            }
            if (!clientAlive) {
                Log.w(TAG, "App did not come back within ${REVIVE_WINDOW_MS}ms — destroying device and exiting")
                destroy()
            }
        }, "uinput-revive").apply { isDaemon = true; start() }
    }

    override fun attachClient(token: android.os.IBinder?) {
        touch()
        if (token == null) return
        clientToken = token
        clientAlive = true
        try {
            token.linkToDeath({ onClientDied(token) }, 0)
            Log.i(TAG, "Client attached")
        } catch (_: android.os.RemoteException) {
            // Already dead by the time we got here.
            onClientDied(token)
        }
    }

    @Volatile private var lastClientCallMs = android.os.SystemClock.uptimeMillis()
    @Volatile private var watchdogStarted = false

    private fun touch() { lastClientCallMs = android.os.SystemClock.uptimeMillis() }

    /**
     * Kill any other instance of this user service still holding a /dev/uinput handle.
     *
     * Each live instance registers a virtual gamepad, mouse and keyboard with Android.
     * Orphans therefore show up to every app as extra, permanently dead controllers, and
     * a game claiming "player 1" generally takes the lowest input device id — i.e. the
     * oldest corpse rather than the live device. Symptom: the controller does nothing in
     * a game that enumerates pads on launch, while the app insists it is connected.
     *
     * Orphans arise two ways: the app process being killed (so ControllerService.onDestroy,
     * and therefore unbind(), never runs), and shizuku_server being restarted (which
     * reparents its previously-spawned user services to init and loses track of them).
     * Neither is preventable from the app side, so we clean up on the way in instead.
     */
    private fun reapStaleInstances() {
        val self = android.os.Process.myPid()
        var killed = 0
        try {
            java.io.File("/proc").listFiles()?.forEach { entry ->
                val pid = entry.name.toIntOrNull() ?: return@forEach
                if (pid == self) return@forEach
                val name = try {
                    // /proc/<pid>/cmdline is NUL-delimited; take the first field. Char(0) avoids
                    // embedding an escape sequence in this source file.
                    java.io.File(entry, "cmdline").readText().substringBefore(Char(0)).trim()
                } catch (_: Throwable) { return@forEach }
                if (name == PROCESS_NAME) {
                    try {
                        android.os.Process.killProcess(pid)
                        killed++
                        Log.i(TAG, "Reaped stale uinput service pid=$pid")
                    } catch (t: Throwable) {
                        Log.w(TAG, "Could not kill stale pid=$pid: ${t.message}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "reapStaleInstances failed: ${t.message}")
        }
        if (killed > 0) Log.i(TAG, "Reaped $killed stale uinput service(s)")
    }

    /** Self-destruct if the client stops calling — see CLIENT_TIMEOUT_MS. */
    private fun startWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        Thread({
            while (true) {
                try { Thread.sleep(5_000) } catch (_: InterruptedException) { return@Thread }
                val idle = android.os.SystemClock.uptimeMillis() - lastClientCallMs
                if (idle > CLIENT_TIMEOUT_MS) {
                    Log.w(TAG, "No client activity for ${idle}ms — destroying device and exiting")
                    destroy()
                    return@Thread
                }
            }
        }, "uinput-watchdog").apply { isDaemon = true; start() }
    }

    @Suppress("unused")
    constructor() : super() {
        Log.i(TAG, "UInputService instantiated (no-arg)")
    }

    @Suppress("unused")
    constructor(context: Context?) : super() {
        Log.i(TAG, "UInputService instantiated (Context=$context)")
    }

    override fun canCreateDevice(): Boolean {
        return try {
            UInputNative.canOpen()
        } catch (t: Throwable) {
            Log.e(TAG, "canOpen failed: ${t.message}")
            false
        }
    }

    override fun createGamepad(profileId: Int): Boolean {
        reapStaleInstances()
        touch()
        startWatchdog()
        if (createdProfile == profileId) {
            // A client returning after being killed. Recreating would make the game see the
            // controller unplug and replug; the device it already has is still good.
            Log.i(TAG, "Reusing existing virtual devices (profile=$profileId)")
            return true
        }
        return try {
            UInputNative.createDevice(profileId).also { ok -> createdProfile = if (ok) profileId else -1 }
        } catch (t: Throwable) {
            createdProfile = -1
            Log.e(TAG, "createDevice failed: ${t.message}")
            false
        }
    }

    override fun sendFrame(
        buttons: Int,
        leftStickX: Int, leftStickY: Int,
        rightStickX: Int, rightStickY: Int,
        leftTrigger: Int, rightTrigger: Int,
        dpadX: Int, dpadY: Int
    ) {
        touch()
        try {
            UInputNative.sendFrame(
                buttons,
                leftStickX, leftStickY,
                rightStickX, rightStickY,
                leftTrigger, rightTrigger,
                dpadX, dpadY
            )
        } catch (t: Throwable) {
            Log.e(TAG, "sendFrame failed: ${t.message}")
        }
    }

    override fun sendMouseFrame(relX: Int, relY: Int, scrollY: Int, keys: Int) {
        touch()
        try {
            UInputNative.sendMouseFrame(relX, relY, scrollY, keys)
        } catch (t: Throwable) {
            Log.e(TAG, "sendMouseFrame failed: ${t.message}")
        }
    }

    override fun pollForceFeedback(): IntArray? {
        touch()
        return try {
            UInputNative.pollFFEvent()
        } catch (t: Throwable) {
            Log.e(TAG, "pollFFEvent failed: ${t.message}")
            null
        }
    }

    override fun runShellCommand(cmd: Array<String>?): Int {
        if (cmd.isNullOrEmpty()) return -1
        return try {
            val proc = ProcessBuilder(cmd.toList())
                .redirectErrorStream(true)
                .start()
            val exit = proc.waitFor()
            Log.i(TAG, "runShellCommand ${cmd.joinToString(" ")} → exit=$exit")
            exit
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommand failed: ${t.message}")
            -1
        }
    }

    override fun runShellCommandForOutput(cmd: Array<String>?): String? {
        if (cmd.isNullOrEmpty()) return null
        return try {
            val proc = ProcessBuilder(cmd.toList())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.trim()
        } catch (t: Throwable) {
            Log.e(TAG, "runShellCommandForOutput failed: ${t.message}")
            null
        }
    }

    override fun destroy() {
        try { UInputNative.destroy() } catch (t: Throwable) {
            Log.e(TAG, "destroy native failed: ${t.message}")
        }
        // Shizuku contract: destroy() must terminate the process
        System.exit(0)
    }
}
