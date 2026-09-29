package com.example.minapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
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
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v3 manual calibration flow (no hardcoded board):
 *  MENU (app) -> start overlay -> [Box] drag box onto real board ->
 *  tap S1 (my striker), S2 (opp striker), T (target), P1/P2 (pockets) ->
 *  lines drawn live. Me/Opp toggles whose striker+pocket is used.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var table: TableView? = null
    private var touchLayer: View? = null
    private var panel: View? = null
    private lateinit var hint: TextView

    private var screenW = 0
    private var screenH = 0
    private var box = RectF()
    private val markers = mutableMapOf<String, PointF>()
    private var activeMark: String? = null
    private var boxAdjust = false
    private var sideMe = true
    private var aimDeg = 0f
    private var manualAim = false

    private val ui = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        screenSize()
        load()
        if (box.width() <= 0f || box.height() <= 0f) {
            val side = screenH.coerceAtMost(screenW) * 0.92f
            val l = (screenW - side) / 2f
            val t = (screenH - side) / 2f
            box = RectF(l, t, l + side, t + side)
        }
        startFg()
        addTable()
        addTouchLayer()
        addPanel()
        refresh("Tap Box, drag it onto the board")
    }

    override fun onDestroy() {
        for (v in listOf(table, touchLayer, panel)) {
            try { if (v != null) wm.removeView(v) } catch (_: Exception) { }
        }
        table = null; touchLayer = null; panel = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    // ---------- setup ----------

    private fun screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            screenW = b.width(); screenH = b.height()
        } else {
            @Suppress("DEPRECATION")
            val s = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getSize(s)
            screenW = s.x; screenH = s.y
        }
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_MIN),
        )
        startForeground(1, Notification.Builder(this, "overlay")
            .setContentTitle("Carrom Vision overlay running")
            .setContentText("Calibrate box + markers, lines follow")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build())
    }

    private fun overlayParams(flags: Int, w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun addTable() {
        val v = TableView(this)
        wm.addView(v, overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ))
        table = v
    }

    private fun addTouchLayer() {
        val v = View(this)
        var dragMode = 0 // 1 move, 2 resize
        var lastX = 0f
        var lastY = 0f
        v.setOnTouchListener { _, e ->
            val t = table ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    when {
                        activeMark != null -> {
                            placeMark(activeMark!!, e.rawX, e.rawY)
                            true
                        }
                        boxAdjust && t.handleAt(e.rawX, e.rawY) -> { dragMode = 2; true }
                        boxAdjust -> { dragMode = 1; true }
                        else -> false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!boxAdjust || dragMode == 0) return@setOnTouchListener true
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    lastX = e.rawX; lastY = e.rawY
                    if (dragMode == 1) {
                        val w = box.width()
                        val nx = (box.left + dx).coerceIn(0f, screenW - w)
                        val ny = (box.top + dy).coerceIn(0f, screenH - w)
                        box = RectF(nx, ny, nx + w, ny + w)
                    } else {
                        val grow = (dx + dy) / 2f
                        val cx = box.centerX()
                        val cy = box.centerY()
                        val half = ((box.width() / 2f + grow)
                            .coerceIn(200f, screenW.coerceAtMost(screenH) / 2f))
                        box = RectF(cx - half, cy - half, cx + half, cy + half)
                    }
                    refresh(null)
                    true
                }
                MotionEvent.ACTION_UP -> { dragMode = 0; save(); true }
                else -> false
            }
        }
        v.visibility = View.GONE
        wm.addView(v, overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        ))
        touchLayer = v
    }

    // ---------- panel ----------

    private fun sbtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            setPadding(8, 4, 8, 4)
            minimumWidth = 0
            setOnClickListener { onClick() }
        }
    }

    private fun row(vararg views: View): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            for (v in views) addView(v)
        }
    }

    private fun addPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(205, 20, 20, 20))
            setPadding(14, 14, 14, 14)
        }
        val header = TextView(this).apply {
            text = "◉ Carrom Vision (drag me)"
            setTextColor(Color.CYAN); textSize = 13f
        }
        hint = TextView(this).apply {
            textSize = 12f; setTextColor(Color.WHITE)
            setPadding(0, 6, 0, 6)
        }
        root.addView(header)
        root.addView(hint)
        root.addView(row(
            sbtn("Box") { toggleBox() },
            sbtn("S1") { arm("S1") },
            sbtn("S2") { arm("S2") },
            sbtn("T") { arm("T") },
        ))
        root.addView(row(
            sbtn("P1") { arm("P1") },
            sbtn("P2") { arm("P2") },
            sbtn("Me/Opp") { sideMe = !sideMe; refresh(null) },
            sbtn("Best") { snapBest() },
        ))
        root.addView(row(
            sbtn("Aim−") { aimDeg = (aimDeg - 2f + 360f) % 360f; manualAim = true; refresh(null) },
            sbtn("Aim+") { aimDeg = (aimDeg + 2f) % 360f; manualAim = true; refresh(null) },
            sbtn("Shot") { captureBoard() },
            sbtn("X") { stopSelf() },
        ))

        val params = overlayParams(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 120 }

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

    // ---------- flow ----------

    private fun arm(key: String) {
        boxAdjust = false
        activeMark = key
        touchLayer?.visibility = View.VISIBLE
        refresh("Tap the board to place $key")
    }

    private fun toggleBox() {
        activeMark = null
        boxAdjust = !boxAdjust
        touchLayer?.visibility = if (boxAdjust) View.VISIBLE else View.GONE
        refresh(if (boxAdjust) "Drag box to move, SE corner to resize. Tap Box when done."
        else "Box locked. Place S1 S2 T P1 P2.")
    }

    private fun placeMark(key: String, x: Float, y: Float) {
        markers[key] = PointF(
            x.coerceIn(box.left, box.right),
            y.coerceIn(box.top, box.bottom),
        )
        if (key == "T") manualAim = false
        activeMark = null
        touchLayer?.visibility = View.GONE
        save()
        refresh("Placed $key. " + nextHint())
    }

    private fun nextHint(): String {
        for (k in listOf("S1", "S2", "T", "P1", "P2")) {
            if (!markers.containsKey(k)) return "Next: place $k."
        }
        return if (sideMe) "Turn: YOU (S1→P1)." else "Turn: OPP (S2→P2)."
    }

    private fun snapBest() {
        val s = markers[if (sideMe) "S1" else "S2"]
        val t = markers["T"]
        val p = markers[if (sideMe) "P1" else "P2"]
        if (s == null || t == null || p == null) {
            toast("Need striker + T + pocket first")
            return
        }
        val (_, deg, cut) = Predictor.aimAt(to600(s), to600(t), to600(p))
        aimDeg = deg
        manualAim = false
        refresh(null)
        toast("Snapped cut=${cut.toInt()}°")
    }

    private fun refresh(msg: String?) {
        if (msg != null) hint.text = msg else hint.text = nextHint() +
            " aim=${aimDeg.toInt()}°"
        val t = table ?: return
        t.box = box
        t.markers = markers.toMap()
        t.boxAdjust = boxAdjust
        t.sideMe = sideMe

        val sKey = if (sideMe) "S1" else "S2"
        val pKey = if (sideMe) "P1" else "P2"
        val s = markers[sKey]
        val pk = markers[pKey]
        if (s == null || pk == null) {
            t.strikerPath = emptyList(); t.coinPath = emptyList()
            t.strikerAfter = emptyList(); t.pocket = null
            t.invalidate()
            return
        }
        val s600 = to600(s)
        val t600 = markers["T"]?.let { to600(it) }
        if (t600 != null && !manualAim) {
            aimDeg = Predictor.aimAt(s600, t600, to600(pk)).second
        }
        val rad = Math.toRadians((aimDeg - 90).toDouble()).toFloat()
        val coins = if (t600 != null)
            listOf(Predictor.Coin(t600.first, t600.second, "white"))
        else emptyList()
        val res = Predictor.predictPath(s600, rad, coins)
        t.strikerPath = res.strikerPath.map { toScreen(it) }
        t.coinPath = res.coinPath.map { toScreen(it) }
        t.strikerAfter = res.strikerAfter.map { toScreen(it) }
        t.pocket = null
        val scale = box.width() / 600f
        val r = Predictor.POCKET_RADIUS * 1.5f * scale
        for (pt in t.coinPath + t.strikerPath + t.strikerAfter) {
            if (hypot((pt.x - pk.x).toDouble(), (pt.y - pk.y).toDouble()) < r) {
                t.pocket = pk
                break
            }
        }
        t.invalidate()
    }

    private fun to600(p: PointF): Pair<Float, Float> =
        ((p.x - box.left) / box.width() * 600f) to
            ((p.y - box.top) / box.height() * 600f)

    private fun toScreen(p: Pair<Float, Float>): PointF =
        PointF(box.left + p.first / 600f * box.width(),
            box.top + p.second / 600f * box.height())

    // ---------- misc ----------

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

    private fun save() {
        val e = getSharedPreferences("table", MODE_PRIVATE).edit()
        e.putFloat("l", box.left); e.putFloat("t", box.top)
        e.putFloat("r", box.right); e.putFloat("b", box.bottom)
        for ((k, m) in markers) {
            e.putFloat("m_${k}x", m.x); e.putFloat("m_${k}y", m.y)
        }
        e.apply()
    }

    private fun load() {
        val p = getSharedPreferences("table", MODE_PRIVATE)
        val l = p.getFloat("l", 0f)
        val t = p.getFloat("t", 0f)
        val r = p.getFloat("r", 0f)
        val b = p.getFloat("b", 0f)
        if (r > l && b > t) box = RectF(l, t, r, b)
        for (k in listOf("S1", "S2", "T", "P1", "P2")) {
            if (p.contains("m_${k}x")) {
                markers[k] = PointF(p.getFloat("m_${k}x", 0f), p.getFloat("m_${k}y", 0f))
            }
        }
    }

    @Suppress("unused")
    private fun dist(a: PointF, b: PointF) =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
}
