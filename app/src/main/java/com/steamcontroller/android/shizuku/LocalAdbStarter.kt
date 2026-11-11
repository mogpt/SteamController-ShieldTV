package com.steamcontroller.android.shizuku

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Starts the Shizuku server by talking to the device's own adb daemon over loopback.
 *
 * WHY THIS EXISTS
 * ---------------
 * Shizuku dies with every reboot, and bringing it back needs shell privilege — the very
 * thing it provides. On a phone that means opening Shizuku and pairing with wireless
 * debugging; on a TV it means the accessibility helper in [ShizukuStarterService] or a PC.
 * Either way the controller is dead after a reboot until somebody does something by hand,
 * which is the opposite of "pick it up and play".
 *
 * The SHIELD has a way out. Its "Network debugging" switch is the legacy `adb tcpip` mode,
 * and unlike Android 11's wireless debugging it persists across reboots: adbd is listening
 * on port 5555 as soon as the box is up. adbd does not care where a client connects from, so
 * this app can be that client — connect to 127.0.0.1, authenticate with its own RSA key, and
 * run Shizuku's starter binary as uid shell. No PC, no accessibility grant, no UI.
 *
 * The first connection raises the system "Allow USB debugging?" dialog for this app's key.
 * Ticking "Always allow" there is the only manual step, once.
 *
 * Where adbd is not listening on TCP (most phones, or a SHIELD with network debugging off)
 * the connect is refused immediately and callers fall back to the older paths.
 */
object LocalAdbStarter {

    private const val TAG = "LocalAdbStarter"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val DEFAULT_ADB_PORT = 5555
    private const val KEY_AUTHORIZED = "authorized"

    enum class Result {
        /** Shizuku's binder reached this process. */
        STARTED,
        /** Shizuku is not installed. */
        NOT_INSTALLED,
        /** Nothing listening on the adb TCP port — network debugging is off. */
        ADB_UNREACHABLE,
        /** Connected, but the user has not (yet) accepted this app's key. */
        NOT_AUTHORIZED,
        /** adb worked; Shizuku still did not come up in time. */
        FAILED,
    }

    private val lock = Any()

    /**
     * True once adbd has accepted this app's key at least once.
     *
     * Background callers must not connect before that. An unauthorized connection makes adbd
     * raise the "Allow USB debugging?" dialog and hold the connection open until the user
     * answers; a receiver has only seconds, so it would hang up with the dialog still on
     * screen. adbd then keeps that prompt queued as pending, stops showing new ones, and the
     * key can never be approved. The first approval therefore has to come from the UI, with
     * a connection that stays open for as long as the user needs.
     */
    fun isAuthorized(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTHORIZED, false)

    /**
     * Blocking. Call from a worker thread.
     *
     * @param interactive true when the user is looking at the app and can answer the
     *   authorization dialog. Background callers pass false and get [Result.NOT_AUTHORIZED]
     *   straight away if the key was never approved.
     * @param timeoutMs overall budget. Keep it under the broadcast ANR limit when calling
     *   from a receiver; be generous when interactive.
     */
    fun start(context: Context, timeoutMs: Long, interactive: Boolean): Result = synchronized(lock) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val shizukuUp = Shizuku.pingBinder()
        if (shizukuUp && isAuthorized(context)) return Result.STARTED
        if (!interactive && !isAuthorized(context)) return Result.NOT_AUTHORIZED

        val starter = try {
            val info = context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            File(info.nativeLibraryDir, "libshizuku.so").absolutePath
        } catch (_: Throwable) {
            return Result.NOT_INSTALLED
        }

        val keyPair = try {
            loadOrCreateKey(context)
        } catch (t: Throwable) {
            Log.e(TAG, "Could not prepare adb key", t)
            return Result.FAILED
        }

        val port = adbTcpPort()
        val socketTimeout = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1000L).toInt()
        try {
            Dadb.create("127.0.0.1", port, keyPair, 2000, socketTimeout).use { adb ->
                // Reaching here means adbd accepted the key.
                prefs(context).edit().putBoolean(KEY_AUTHORIZED, true).apply()
                if (shizukuUp) return Result.STARTED
                val response = adb.shell(starter)
                Log.i(TAG, "Shizuku starter exit=${response.exitCode}: ${response.allOutput.trim().takeLast(200)}")
                if (response.exitCode != 0) return Result.FAILED
            }
        } catch (t: java.net.ConnectException) {
            Log.i(TAG, "adbd not reachable on 127.0.0.1:$port (${t.message})")
            return Result.ADB_UNREACHABLE
        } catch (t: java.net.SocketTimeoutException) {
            // adbd holds the connection open while the authorization dialog is on screen. An
            // approved key connects instantly, so a timeout means it is not (or no longer)
            // approved — stop background attempts until the user approves it again.
            Log.w(TAG, "adb connection timed out — key not authorized")
            prefs(context).edit().putBoolean(KEY_AUTHORIZED, false).apply()
            return Result.NOT_AUTHORIZED
        } catch (t: Throwable) {
            Log.w(TAG, "adb start failed: $t")
            return Result.FAILED
        }

        // The server hands its binder to client apps asynchronously after it starts.
        val binderDeadline = maxOf(deadline, SystemClock.elapsedRealtime() + 3000L)
        while (SystemClock.elapsedRealtime() < binderDeadline) {
            if (Shizuku.pingBinder()) return Result.STARTED
            Thread.sleep(100)
        }
        Log.w(TAG, "Starter ran but the Shizuku binder never arrived")
        Result.FAILED
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("local_adb", Context.MODE_PRIVATE)

    private fun loadOrCreateKey(context: Context): AdbKeyPair {
        val priv = File(context.filesDir, "adbkey")
        val pub = File(context.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) {
            AdbKeyPair.generate(priv, pub)
            Log.i(TAG, "Generated adb key")
        }
        return AdbKeyPair.read(priv, pub)
    }

    /** adbd's TCP port, from the same property adbd itself reads. */
    private fun adbTcpPort(): Int = try {
        val get = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "service.adb.tcp.port", "") as String).toIntOrNull()
            ?.takeIf { it in 1..65535 } ?: DEFAULT_ADB_PORT
    } catch (_: Throwable) {
        DEFAULT_ADB_PORT
    }
}
