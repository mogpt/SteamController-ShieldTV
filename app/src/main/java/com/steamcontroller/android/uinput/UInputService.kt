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
        return try {
            UInputNative.createDevice(profileId)
        } catch (t: Throwable) {
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
