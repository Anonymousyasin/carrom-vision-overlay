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
    fun capture(absPath: String): Boolean {        if (!isRunning() || !isGranted()) return false
        return try {
            // newProcess is hidden in api 13.x — reach it via reflection
            val m = Shizuku::class.java.methods.firstOrNull {
                it.name == "newProcess" && it.parameterTypes.size == 3
            } ?: return false
            val args: Array<Any?> = arrayOf(arrayOf("screencap", "-p", absPath), null, null)
            val p = m.invoke(null, *args) as? Process
            p?.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun statusText(): String = when {
        !isRunning() -> "Shizuku: not running (start Shizuku app first)"
        !isGranted() -> "Shizuku: running, permission needed"
        else -> "Shizuku: ready"
    }

    /** Screencap straight to a (possibly downsampled) Bitmap for live loops. */
    fun captureBitmap(cacheDir: java.io.File, maxDim: Int = 1280): android.graphics.Bitmap? {
        return try {
            val f = java.io.File(cacheDir, "auto_frame.png")
            if (!capture(f.absolutePath)) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            val md = bounds.outWidth.coerceAtLeast(bounds.outHeight)
            while (md / sample > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}
