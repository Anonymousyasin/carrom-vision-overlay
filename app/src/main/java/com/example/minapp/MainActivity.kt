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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusOverlay: TextView
    private lateinit var statusShizuku: TextView
    private lateinit var sideBtn: Button
    private lateinit var samplesView: TextView

    private fun samplesText(): String {
        val n = SampleExporter.count(this)
        return "Training samples: $n in Download/CarromSamples (tap to browse)"
    }

    private fun tuneEngine() {
        val bar = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal,
        ).apply { max = 100 }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("Tuning engine…")
            .setView(bar)
            .setCancelable(false)
            .show()
        Thread {
            val res = TuneRunner.tune(this, 60) { p ->
                runOnUiThread { bar.progress = p }
            }
            runOnUiThread {
                dlg.dismiss()
                android.app.AlertDialog.Builder(this)
                    .setTitle(if (res.improved) "Engine improved → v${res.version} 🎉" else "Engine v${res.version}")
                    .setMessage(res.detail)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun engineStats() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Training engine")
            .setMessage(TuneRunner.statsText(this))
            .setPositiveButton("Share brain", null)
            .setNegativeButton("Close", null)
            .show()
            .also { d ->
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    TuneRunner.shareBrain(this)
                    toast("brain.json shared to Download/CarromSamples")
                }
            }
    }

    private fun browseSamples() {
        val items = SampleExporter.list(this)
        if (items.isEmpty()) {
            toast("No samples yet — analyze + Save one")
            return
        }
        val names = items.map { it.id }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Samples (${items.size})")
            .setItems(names) { _, which ->
                val id = names[which]
                val js = SampleExporter.readJson(this, id) ?: "unreadable"
                android.app.AlertDialog.Builder(this)
                    .setTitle(id)
                    .setMessage(js.take(2000))
                    .setPositiveButton("Share") { _, _ -> SampleExporter.share(this, id) }
                    .setNegativeButton("Delete") { _, _ ->
                        SampleExporter.delete(this, id)
                        samplesView.text = samplesText()
                    }
                    .setNeutralButton("Close", null)
                    .show()
            }
            .show()
    }

    private fun isWhite(): Boolean =
        getSharedPreferences("cv_prefs", MODE_PRIVATE).getBoolean("playWhite", true)

    private val shizukuListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            refreshStatus()
            toast(
                if (grantResult == PackageManager.PERMISSION_GRANTED) "Shizuku granted"
                else "Shizuku denied",
            )
        }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, AnalyzerActivity::class.java).putExtra("uri", uri),
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusOverlay = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }
        statusShizuku = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER }

        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }

        sideBtn = Button(this).apply {
            textSize = 18f
            setOnClickListener {
                val w = !isWhite()
                getSharedPreferences("cv_prefs", MODE_PRIVATE).edit()
                    .putBoolean("playWhite", w).apply()
                refreshSide()
                toast(if (w) "You play WHITE" else "You play BLACK")
            }
        }

        val root = android.widget.ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(TextView(context).apply {
                    text = "◉ Carrom Vision"
                    textSize = 24f; gravity = Gravity.CENTER
                })
                addView(statusOverlay)
                addView(statusShizuku)
                addView(sideBtn)
            addView(btn("1. Grant overlay permission") { askOverlayPermission() })
            addView(btn("2. Request Shizuku permission") { ShizukuCap.requestPermission(100); })
            addView(btn("3. Start overlay over Carrom Pool") { startOverlay() })
            addView(btn("4. Analyze screenshot (accurate)") { pickImage.launch("image/*") })
            addView(btn("Stop overlay") { stopService(Intent(this@MainActivity, OverlayService::class.java)) })
            addView(TextView(context).apply {
                textSize = 14f; gravity = Gravity.CENTER
                text = samplesText()
                setOnClickListener { browseSamples() }
            }.also { samplesView = it })
            addView(btn("Share all samples") { SampleExporter.share(this@MainActivity, null) })
            addView(btn("Tune engine (learn from samples)") { tuneEngine() })
            addView(btn("Engine stats + share brain") { engineStats() })
            })
        }
        setContentView(root)

        Thread {
            val moved = SampleExporter.migrateLegacy(this)
            runOnUiThread {
                if (moved > 0) toast("Moved $moved old samples to Download/CarromSamples")
                if (::samplesView.isInitialized) samplesView.text = samplesText()
            }
        }.start()

        Shizuku.addRequestPermissionResultListener(shizukuListener)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshSide()
        if (::samplesView.isInitialized) samplesView.text = samplesText()
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

    private fun refreshSide() {
        val w = isWhite()
        sideBtn.text = if (w) "⬤ I play WHITE (tap for Black)" else "⬤ I play BLACK (tap for White)"
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
