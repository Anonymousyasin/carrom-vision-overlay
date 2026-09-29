package com.example.minapp

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusShizuku: TextView
    private lateinit var statusDemo: TextView

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshStatus()
            toast(
                if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku granted"
                else "Shizuku denied",
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusOverlay = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        statusShizuku = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        statusDemo = TextView(this).apply {
            textSize = 14f; gravity = Gravity.CENTER
            setPadding(16, 16, 16, 16)
        }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(TextView(context).apply {
                text = "◉ Carrom Vision"
                textSize = 24f; gravity = Gravity.CENTER
            })
            addView(statusOverlay)
            addView(statusShizuku)
            addView(btn("1. Grant overlay permission") { askOverlayPermission() })
            addView(btn("2. Request Shizuku permission") { ShizukuCap.requestPermission(100); })
            addView(btn("3. Start overlay over Carrom Pool") { startOverlay() })
            addView(btn("Stop overlay") { stopService(Intent(this@MainActivity, OverlayService::class.java)) })
            addView(btn("Offline demo predict") { demo() })
            addView(statusDemo)
        }
        setContentView(root)

        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    private fun refreshStatus() {
        val ok = Settings.canDrawOverlays(this)
        statusOverlay.text = if (ok) "Overlay: granted ✓" else "Overlay: NOT granted — tap 1"
        statusShizuku.text = ShizukuCap.statusText()
    }

    private fun askOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("Overlay already granted")
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Grant overlay permission first (button 1)")
            askOverlayPermission()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
        toast("Overlay started — open Carrom Pool")
    }

    private fun demo() {
        val (striker, coins) = Predictor.demoState()
        val pred = Predictor.fullPrediction(striker, coins)
        statusDemo.text = pred.best?.let {
            "BEST: ${it.reason} angle=${"%.1f".format(it.angleDeg)} score=${"%.3f".format(it.score)}"
        } ?: "No clean pot found"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
