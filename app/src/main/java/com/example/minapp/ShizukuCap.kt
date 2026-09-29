package com.example.minapp

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku privileged shell: fast screencap bursts for board-frame tuning. */
object ShizukuCap {

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Exception) {
        false
    }

    fun isGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    fun requestPermission(code: Int) {
        try {
            if (!isGranted()) Shizuku.requestPermission(code)
        } catch (_: Exception) { }
    }

    /** Capture screen to absolute path via privileged shell. Null on failure. */
    fun capture(absPath: String): Boolean {
        if (!isRunning() || !isGranted()) return false
        return try {
            // screencap runs as shell/adb identity through Shizuku, no root needed
            val p = Shizuku.newProcess(arrayOf("screencap", "-p", absPath), null, null)
            p.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun statusText(): String = when {
        !isRunning() -> "Shizuku: not running (start Shizuku app first)"
        !isGranted() -> "Shizuku: running, permission needed"
        else -> "Shizuku: ready"
    }
}
