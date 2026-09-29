package com.example.minapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Real-time transparent overlay over Carrom Pool.
 * Fullscreen drawing layer is NOT_TOUCHABLE so game touches pass through.
 * Small control panel stays touchable: aim / striker / zoom + Best + Shot + Close.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var drawView: OverlayView? = null
    private var panel: View? = null

    // 600-space state (same convention as Predictor / web app)
    private var striker: Pair<Float, Float> = 460f to 360f
    private var aimDeg: Float = 242f
    private var zoom: Float = 1f
    private var coins: List<Predictor.Coin> = emptyList()

    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        coins = Predictor.demoState().second
        startFg()
        addDrawingLayer()
        addPanel()
        refresh()
    }

    override fun onDestroy() {
        try { drawView?.let { wm.removeView(it) } } catch (_: Exception) { }
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) { }
        drawView = null
        panel = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    private fun startFg() {
        val ch = "overlay"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Overlay", NotificationManager.IMPORTANCE_MIN),
        )
        val n = Notification.Builder(this, ch)
            .setContentTitle("Carrom Vision overlay running")
            .setContentText("Transparent aim lines over Carrom Pool")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n)
    }

    private fun addDrawingLayer() {
        val v = OverlayView(this)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        wm.addView(v, p)
        drawView = v
    }

    private fun seekRow(label: String, max: Int, init: Int, onChange: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tv = TextView(this).apply { text = label; setTextColor(Color.WHITE); textSize = 11f }
        val sb = SeekBar(this).apply {
            this.max = max
            progress = init
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    if (fromUser) { onChange(v); tv.text = "$label $v" }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { }
                override fun onStopTrackingTouch(s: SeekBar?) { }
            })
        }
        row.addView(tv)
        row.addView(sb)
        return row
    }

    private fun addPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 20, 20, 20))
            setPadding(16, 16, 16, 16)
        }
        val header = TextView(this).apply {
            text = "◉ Carrom Vision (drag me)"
            setTextColor(Color.CYAN)
            textSize = 13f
            setPadding(0, 0, 0, 8)
        }
        root.addView(header)
        root.addView(seekRow("Aim", 360, aimDeg.toInt()) { aimDeg = it.toFloat(); refresh() })
        root.addView(seekRow("StrX", 550, striker.first.toInt()) {
            striker = it.toFloat() to striker.second; refresh()
        })
        root.addView(seekRow("StrY", 550, striker.second.toInt()) {
            striker = striker.first to it.toFloat(); refresh()
        })
        root.addView(seekRow("Zoom", 100, 100) {
            zoom = (it.coerceAtLeast(70)) / 100f; refresh()
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val best = Button(this).apply {
            text = "Best"
            setOnClickListener {
                Predictor.bestShot(striker, coins)?.let {
                    aimDeg = it.angleDeg
                    refresh()
                    toast("Best: ${it.reason}")
                } ?: toast("No clean pot")
            }
        }
        val shot = Button(this).apply {
            text = "Shot"
            setOnClickListener { captureBoard() }
        }
        val close = Button(this).apply {
            text = "X"
            setOnClickListener { stopSelf() }
        }
        btnRow.addView(best)
        btnRow.addView(shot)
        btnRow.addView(close)
        root.addView(btnRow)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 120 }

        // drag panel by header
        var dx = 0f
        var dy = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - params.x; dy = e.rawY - params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (e.rawX - dx).toInt()
                    params.y = (e.rawY - dy).toInt()
                    wm.updateViewLayout(root, params)
                    true
                }
                else -> false
            }
        }
        wm.addView(root, params)
        panel = root
    }

    private fun refresh() {
        val rad = Math.toRadians((aimDeg - 90).toDouble()).toFloat()
        val (sPath, hit, cPath) = Predictor.predictPath(striker, rad, coins)
        var pocket: Pair<Float, Float>? = null
        for (pt in cPath + sPath) {
            for (pk in Predictor.POCKETS) {
                val d = kotlin.math.hypot(
                    (pt.first - pk.first).toDouble(),
                    (pt.second - pk.second).toDouble(),
                )
                if (d < Predictor.POCKET_RADIUS * 1.5f) { pocket = pk; break }
            }
            if (pocket != null) break
        }
        drawView?.boardZoom = zoom
        drawView?.update(striker, coins, Predictor.Prediction(sPath, hit, cPath, pocket, null))
    }

    private fun captureBoard() {
        toast("Capturing via Shizuku…")
        Thread {
            val out = File(cacheDir, "board_shot.png").absolutePath
            val ok = ShizukuCap.capture(out)
            ui.post { toast(if (ok) "Saved: $out" else "Capture failed — check Shizuku") }
        }.start()
    }

    private fun toast(msg: String) {
        ui.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }
}
