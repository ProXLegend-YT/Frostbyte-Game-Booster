package com.frostbyte.gamebooster.service

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Elevated access for the live touch recorder. Two ways, no matter which your phone has:
 *  - Shizuku: a free app that uses Android's built-in Wireless debugging (no root, no PC on Android 11+).
 *  - Root (su): if the phone is rooted.
 * Both are used only to run the system's own `getevent` tool, which logs your real touches.
 */
object Priv {
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val REQ_CODE = 4242

    fun shizukuInstalled(c: Context): Boolean =
        try { c.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }

    /** Shizuku's service is started (you did the Wireless-debugging step inside the Shizuku app). */
    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    /** Shizuku is running AND you allowed FrostByte in it. */
    fun shizukuGranted(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Shows Shizuku's "Allow FrostByte?" dialog. */
    fun requestShizuku() {
        try { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQ_CODE) } catch (_: Throwable) { }
    }

    /** Starts `sh -c <cmd>` with elevated rights, through Shizuku or through su. */
    @Suppress("DEPRECATION")
    fun spawn(cmd: String, viaShizuku: Boolean): Process =
        if (viaShizuku) Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
        else ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
}
