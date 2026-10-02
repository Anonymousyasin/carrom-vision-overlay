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

    /** Batch auto-label: pick screenshots → heuristic centered-square box →
     *  tool-model detect → unverified samples (review flags set). */
    private fun batchAutoLabel(uris: List<android.net.Uri>) {
        val m = ToolModel.loadFromApp(this) ?: run {
            toast("No model — import it first (button above)")
            return
        }
        val total = uris.size
        toast("Auto-labelling $total…")
        Thread {
            var done = 0
            var fail = 0
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: Exception) { }
                try {
                    val bm = decodeSampled(uri, 2048)
                    if (bm == null) {
                        fail++
                        continue
                    }
                    val fullW = try {
                        val b = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, b)
                        }
                        b.outWidth
                    } catch (_: Exception) {
                        bm.width
                    }
                    val scale = if (bm.width > 0) fullW.toFloat() / bm.width else 1f
                    // heuristic board box: largest centered square
                    val side = bm.width.coerceAtMost(bm.height).toFloat()
                    val box = android.graphics.RectF(
                        (bm.width - side) / 2f, (bm.height - side) / 2f,
                        (bm.width + side) / 2f, (bm.height + side) / 2f,
                    )
                    val crop = ToolDetect.crop600(bm, box)
                    val res = ToolDetect.detectBoard(crop, m)
                    try {
                        crop.recycle()
                    } catch (_: Exception) { }
                    fun bx(x: Float) = box.left + x / 600f * box.width()
                    fun by(y: Float) = box.top + y / 600f * box.height()
                    fun br(r: Float) = r / 600f * box.width()
                    val markers = HashMap<String, android.graphics.PointF>()
                    val radii = HashMap<String, Float>()
                    res.striker?.let { s ->
                        markers["S"] = android.graphics.PointF(bx(s.x), by(s.y))
                        radii["S"] = br(s.r)
                    }
                    val keys = listOf("P1", "P2", "P3", "P4")
                    for ((i, p) in res.pockets.withIndex()) {
                        if (i >= keys.size) break
                        markers[keys[i]] = android.graphics.PointF(bx(p.x), by(p.y))
                        radii[keys[i]] = br(p.r)
                    }
                    val coins = res.coins.map {
                        SampleExporter.InCoin(
                            android.graphics.PointF(bx(it.x), by(it.y)),
                            it.type, br(it.r),
                        )
                    }
                    val flags = ArrayList<String>()
                    if (coins.isEmpty()) flags.add("no coins found")
                    if (res.striker == null) flags.add("striker not found")
                    val npk = res.pockets.size
                    if (npk < 4) flags.add("only $npk/4 pockets")
                    val white = isWhite()
                    val id = SampleExporter.export(
                        this, uri, bm,
                        scale, box, markers, radii, coins, white, null,
                        verified = false, source = "generator",
                        reviewFlags = flags,
                    )
                    try {
                        bm.recycle()
                    } catch (_: Exception) { }
                    if (id != null) done++ else fail++
                } catch (_: Exception) {
                    fail++
                }
            }
            runOnUiThread {
                if (::samplesView.isInitialized) samplesView.text = samplesText()
                toast("Auto-labelled $done/$total (failed $fail) — verify before training")
            }
        }.start()
    }

    private fun decodeSampled(uri: android.net.Uri, maxDim: Int): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val md = bounds.outWidth.coerceAtLeast(bounds.outHeight)
            while (md / sample > maxDim) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun browseSamples() {        val items = SampleExporter.list(this)
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

    private val pickData =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                startActivity(
                    Intent(this, AnalyzerActivity::class.java)
                        .putExtra("uri", uri)
                        .putExtra("mode", "data"),
                )
            }
        }

    private val pickModel =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) { }
            Thread {
                val ok = try {
                    contentResolver.openInputStream(uri)?.use { ins ->
                        openFileOutput("carrom_model.json", MODE_PRIVATE).use { outs ->
                            ins.copyTo(outs)
                        }
                    }
                    ToolModel.loadFromApp(this) != null
                } catch (_: Exception) {
                    false
                }
                runOnUiThread {
                    toast(if (ok) "Model imported ✓ — Tool button is live" else "Import failed (not a valid model?)")
                }
            }.start()
        }

    private val pickBatch =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) batchAutoLabel(uris)
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
            addView(btn("📦 Data Gen — mark + save samples") { pickData.launch("image/*") })
            addView(btn("⬇ Import model (carrom_model.json)") { pickModel.launch("*/*") })
            addView(btn("🤖 Batch auto-label → samples") { pickBatch.launch(arrayOf("image/*")) })
            addView(btn("Stop overlay") { stopService(Intent(this@MainActivity, OverlayService::class.java)) })
            addView(TextView(context).apply {
                textSize = 14f; gravity = Gravity.CENTER
                text = samplesText()
                setOnClickListener { browseSamples() }
            }.also { samplesView = it })
            addView(btn("Share all samples") { SampleExporter.share(this@MainActivity, null) })

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
