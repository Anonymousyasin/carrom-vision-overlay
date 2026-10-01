package com.example.minapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * v5 screenshot analyzer view. State in IMAGE pixels. View matrix =
 * fit-scale * zoom + pan; pinch zooms (1..4x), 1-finger drag pans,
 * quick tap places (time/distance gate so pinch never misplaces).
 */
class AnalyzerView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
    var box = RectF()
    var boxInitDone = false
    val markers = mutableMapOf<String, PointF>() // S, P1..P4 (image px)
    /** Tapped radii, image px. Defaults set at placement from box scale. */
    val markRadii = mutableMapOf<String, Float>()
    val coins = mutableListOf<CoinMark>() // image px, type locked by wizard step

    data class CoinMark(val p: PointF, val type: String, var r: Float = 0f) {
        var conf: Float = 1f
        var touched: Boolean = true // manual taps start confirmed
    }

    /** Selection for resize: coin index or named marker key. */
    var selCoin = -1
    var selMark: String? = null

    var activeMark: String? = null // BOX, BLACK, WHITE, QUEEN, S, POCKET
    var onChanged: (() -> Unit)? = null

    var strikerPath: List<PointF> = emptyList()
    var coinPath: List<PointF> = emptyList()
    var strikerAfter: List<PointF> = emptyList()
    /** Second-ball path for COMBO shots (orange). */
    var comboPath: List<PointF> = emptyList()
    var bestTarget: PointF? = null
    var pocket: PointF? = null

    // ---- view matrix ----
    private var baseScale = 1f
    private var baseX = 0f
    private var baseY = 0f
    var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private fun fit() {
        val bm = bitmap ?: return
        if (width == 0 || height == 0) return
        baseScale = minOf(width / bm.width.toFloat(), height / bm.height.toFloat())
            .coerceAtLeast(1e-6f)
        baseX = (width - bm.width * baseScale) / 2f
        baseY = (height - bm.height * baseScale) / 2f
    }

    private fun totalScale() = baseScale * zoom
    private fun toViewX(x: Float) = baseX + panX + x * totalScale()
    private fun toViewY(y: Float) = baseY + panY + y * totalScale()
    fun toImage(vx: Float, vy: Float): PointF =
        PointF((vx - baseX - panX) / totalScale(), (vy - baseY - panY) / totalScale())

    /** Default radius for a piece kind, image px (from box scale). */
    fun defaultR(kind: String): Float = when (kind) {
        "S" -> box.width() * 20f / 600f
        "P" -> box.width() * 25f / 600f
        else -> box.width() * 15f / 600f
    }

    fun coinR(i: Int): Float =
        coins.getOrNull(i)?.let { if (it.r > 0f) it.r else defaultR("C") }
            ?: defaultR("C")

    fun markR(key: String): Float =
        markRadii[key] ?: defaultR(if (key == "S") "S" else "P")

    /** Stepper fallback: grow/shrink selected circle. */
    fun adjustSelected(factor: Float) {
        val lo = box.width() * 5f / 600f
        val hi = box.width() * 60f / 600f
        if (selCoin >= 0 && selCoin < coins.size) {
            val c = coins[selCoin]
            c.r = ((if (c.r > 0f) c.r else defaultR("C")) * factor).coerceIn(lo, hi)
        } else if (selMark != null) {
            markRadii[selMark!!] = (markR(selMark!!) * factor).coerceIn(lo, hi)
        }
        onChanged?.invoke()
        invalidate()
    }

    /** Circle under point: coin index (-1) + marker key (null). */
    private fun circleAt(ip: PointF): Pair<Int, String?> {
        val tol = 70f / totalScale()
        var bestD = Float.MAX_VALUE
        var bi = -1
        var bk: String? = null
        for ((i, c) in coins.withIndex()) {
            val d = hypot((ip.x - c.p.x).toDouble(), (ip.y - c.p.y).toDouble()).toFloat()
            if (d < coinR(i) + tol && d < bestD) {
                bestD = d; bi = i; bk = null
            }
        }
        for ((k, m) in markers) {
            val d = hypot((ip.x - m.x).toDouble(), (ip.y - m.y).toDouble()).toFloat()
            if (d < markR(k) + tol && d < bestD) {
                bestD = d; bi = -1; bk = k
            }
        }
        return bi to bk
    }

    fun resetZoom() {
        zoom = 1f; panX = 0f; panY = 0f
        invalidate()
    }

    // ---- touch: tap gate + drag + pinch ----
    private var mode = 0 // 0 idle, 1 tap?, 2 pan/move/resize, 3 pinch
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var boxDrag = 0 // 1 move, 2 resize
    private var grabKind = 0 // 0 none, 1 move circle, 2 resize circle
    private var grabCoin = -1
    private var grabMark: String? = null
    private var pinchStart = 0f
    private var zoomStart = 1f
    private var focusX = 0f
    private var focusY = 0f

    init {
        setOnTouchListener { _, e ->
            val bm = bitmap ?: return@setOnTouchListener false
            fit()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                    downT = e.eventTime
                    mode = 1
                    grabKind = 0; grabCoin = -1; grabMark = null
                    if (activeMark == "BOX") {
                        val ip = toImage(e.x, e.y)
                        val tol = 70f / totalScale()
                        boxDrag = if (hypot(
                                (ip.x - box.right).toDouble(),
                                (ip.y - box.bottom).toDouble(),
                            ) < tol
                        ) 2 else 1
                    } else {
                        // grab existing circle for MOVE (tap-first: no ring resize)
                        val ip = toImage(e.x, e.y)
                        val (ci, mk) = circleAt(ip)
                        if (ci >= 0 || mk != null) {
                            grabKind = 1
                            grabCoin = ci
                            grabMark = mk
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount == 2) {
                        mode = 3
                        pinchStart = span(e)
                        zoomStart = zoom
                        focusX = (e.getX(0) + e.getX(1)) / 2f
                        focusY = (e.getY(0) + e.getY(1)) / 2f
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    when (mode) {
                        3 -> {
                            if (e.pointerCount >= 2) {
                                val s = span(e)
                                if (pinchStart > 0) {
                                    val fx = (e.getX(0) + e.getX(1)) / 2f
                                    val fy = (e.getY(0) + e.getY(1)) / 2f
                                    setZoom(zoomStart * s / pinchStart, fx, fy)
                                    panX += fx - focusX
                                    panY += fy - focusY
                                    focusX = fx; focusY = fy
                                }
                                invalidate()
                            }
                            true
                        }
                        1 -> {
                            if (hypot(
                                    (e.x - downX).toDouble(),
                                    (e.y - downY).toDouble(),
                                ) > 12f
                            ) {
                                mode = 2 // becomes pan (or box drag below)
                            }
                            if (mode == 2) {
                                lastX = e.x; lastY = e.y
                            }
                            true
                        }
                        2 -> {
                            if (activeMark == "BOX" && boxDrag != 0) {
                                // box drag in image coords
                                val a = toImage(lastX, lastY)
                                val b = toImage(e.x, e.y)
                                moveBox(b.x - a.x, b.y - a.y, boxDrag == 2, bm)
                                lastX = e.x; lastY = e.y
                                onChanged?.invoke()
                            } else if (grabKind != 0) {
                                // circle move / resize in image coords
                                val b = toImage(e.x, e.y)
                                dragCircle(b, bm)
                                onChanged?.invoke()
                            } else {
                                panX += e.x - lastX
                                panY += e.y - lastY
                                clampPan()
                                lastX = e.x; lastY = e.y
                            }
                            invalidate()
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasTap = mode == 1 &&
                        hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < 12f &&
                        e.eventTime - downT < 400
                    mode = 0
                    boxDrag = 0
                    if (wasTap && e.actionMasked == MotionEvent.ACTION_UP) {
                        handleTap(toImage(e.x, e.y), bm)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun span(e: MotionEvent): Float =
        hypot(
            (e.getX(0) - e.getX(1)).toDouble(),
            (e.getY(0) - e.getY(1)).toDouble(),
        ).toFloat()

    private fun setZoom(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(1f, 4f)
        // keep focus point stable: pan compensates scale change around focus
        val k = nz / zoom
        panX = fx - baseX - (fx - baseX - panX) * k
        panY = fy - baseY - (fy - baseY - panY) * k
        zoom = nz
        clampPan()
    }

    private fun clampPan() {
        val bm = bitmap ?: return
        val w = bm.width * totalScale()
        val h = bm.height * totalScale()
        // keep at least 1/4 of the image on screen each side
        panX = panX.coerceIn(width * 0.25f - w, width * 0.75f)
        panY = panY.coerceIn(height * 0.25f - h, height * 0.75f)
    }

    private fun moveBox(dx: Float, dy: Float, resize: Boolean, bm: Bitmap) {
        if (!resize) {
            val w = box.width()
            val nx = (box.left + dx).coerceIn(0f, bm.width - w)
            val ny = (box.top + dy).coerceIn(0f, bm.height - w)
            box = RectF(nx, ny, nx + w, ny + w)
        } else {
            val grow = (dx + dy) / 2f
            val cx = box.centerX()
            val cy = box.centerY()
            val half = (box.width() / 2f + grow)
                .coerceIn(100f, bm.width.coerceAtMost(bm.height) / 2f)
            box = RectF(cx - half, cy - half, cx + half, cy + half)
        }
    }

    private fun clampBmp(ip: PointF, bm: Bitmap): PointF = PointF(
        ip.x.coerceIn(0f, bm.width.toFloat()),
        ip.y.coerceIn(0f, bm.height.toFloat()),
    )

    /** Drag grabbed circle: move center (tap-first; sizing via R±). */
    private fun dragCircle(ip: PointF, bm: Bitmap) {
        if (grabCoin >= 0 && grabCoin < coins.size) {
            val c = coins[grabCoin]
            if (!c.touched) {
                // moving an auto mark = a correction: training signal
                c.touched = true
                try {
                    TuneRunner.noteCorrection(context)
                } catch (_: Exception) { }
            }
            val p = clampBmp(ip, bm)
            c.p.x = p.x; c.p.y = p.y
            selCoin = grabCoin; selMark = null
        } else if (grabMark != null) {
            val m = markers[grabMark] ?: return
            val p = clampBmp(ip, bm)
            m.x = p.x; m.y = p.y
            selMark = grabMark; selCoin = -1
        }
    }

    private fun handleTap(ip: PointF, bm: Bitmap) {
        // tap on an existing circle = select it for resize (no new mark)
        val (ci, mk) = circleAt(ip)
        if (ci >= 0 || mk != null) {
            selCoin = ci
            selMark = mk
            if (ci >= 0) coins[ci].touched = true // confirm by touch
            invalidate()
            return
        }
        val c = clampBmp(ip, bm)
        when (activeMark) {
            "S" -> {
                markers["S"] = c
                selMark = "S"; selCoin = -1
                onChanged?.invoke()
                invalidate()
            }
            "POCKET" -> {
                val n = markers.keys.count { it.startsWith("P") } + 1
                if (n <= 4) {
                    val k = "P$n"
                    markers[k] = c
                    selMark = k; selCoin = -1
                    onChanged?.invoke()
                    invalidate()
                }
            }
            "BLACK", "WHITE", "QUEEN" -> {
                val t = when (activeMark) {
                    "BLACK" -> "black"
                    "WHITE" -> "white"
                    else -> "red"
                }
                if (t == "red") coins.removeAll { it.type == "red" }
                if (coins.size < 20) {
                    coins.add(CoinMark(c, t))
                    selCoin = coins.size - 1; selMark = null
                    onChanged?.invoke()
                    invalidate()
                }
            }
            else -> { } // BOX mode uses drag; null = explore only
        }
    }

    // ---- pulse last marker so the active step is visible ----
    private var pulseOn = true
    private val pulseTick = object : Runnable {
        override fun run() {
            pulseOn = !pulseOn
            invalidate()
            pulseHandler.postDelayed(this, 550)
        }
    }
    private val pulseHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseHandler.post(pulseTick)
    }

    override fun onDetachedFromWindow() {
        pulseHandler.removeCallbacks(pulseTick)
        super.onDetachedFromWindow()
    }

    // ---- draw ----
    private val boxPaint = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }
    private val boxActivePaint = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val handlePaint = Paint().apply {
        color = Color.CYAN; style = Paint.Style.FILL; isAntiAlias = true
    }
    private val strikerLine = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 6f
        pathEffect = DashPathEffect(floatArrayOf(14f, 9f), 0f); isAntiAlias = true
    }
    private val coinLine = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }
    private val afterPaint = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f); isAntiAlias = true
    }
    private val comboPaint = Paint().apply {
        color = Color.rgb(255, 165, 0); style = Paint.Style.STROKE; strokeWidth = 6f
        isAntiAlias = true
    }
    private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val pulsePaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 4f; isAntiAlias = true
    }

    fun coinColor(t: String): Int = when (t) {
        "white" -> Color.parseColor("#f0e6d2")
        "black" -> Color.parseColor("#333333")
        "red" -> Color.parseColor("#e53e3e")
        else -> Color.CYAN
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return
        fit()
        if (!boxInitDone) {
            val side = bm.width.coerceAtMost(bm.height) * 0.85f
            box = RectF(
                (bm.width - side) / 2f, (bm.height - side) / 2f,
                (bm.width + side) / 2f, (bm.height + side) / 2f,
            )
            boxInitDone = true
        }
        val s = totalScale()
        canvas.save()
        canvas.translate(baseX + panX, baseY + panY)
        canvas.scale(s, s)
        canvas.drawBitmap(bm, 0f, 0f, null)
        val px = 1f / s // 1 screen px in image units

        canvas.drawRect(box, if (activeMark == "BOX") boxActivePaint else boxPaint)
        for ((cx, cy) in listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom,
        )) canvas.drawCircle(cx, cy, 16f * px, handlePaint)

        // old fixed-dot marks: B1.., W1.., Q
        val counters = mutableMapOf("black" to 0, "white" to 0, "red" to 0)
        for ((i, c) in coins.withIndex()) {
            val n = (counters[c.type] ?: 0) + 1
            counters[c.type] = n
            val label = when (c.type) {
                "black" -> "B$n"
                "white" -> "W$n"
                else -> "Q"
            }
            val rr = 15f * px
            dotPaint.color = coinColor(c.type)
            canvas.drawCircle(c.p.x, c.p.y, rr, dotPaint)
            ringPaint.color = Color.BLACK
            ringPaint.strokeWidth = 3f * px
            canvas.drawCircle(c.p.x, c.p.y, rr, ringPaint)
            labelPaint.textSize = 30f * px
            canvas.drawText(label, c.p.x + 20f * px, c.p.y + 10f * px, labelPaint)
            if (selCoin == i) {
                ringPaint.color = Color.YELLOW
                ringPaint.strokeWidth = 4f * px
                canvas.drawCircle(c.p.x, c.p.y, rr + 8f * px, ringPaint)
            } else if (!c.touched && c.conf < 0.85f) {
                // gated review: unsure auto-mark pulses amber until confirmed
                ringPaint.color = Color.rgb(255, 165, 0)
                ringPaint.strokeWidth = 4f * px
                canvas.drawCircle(c.p.x, c.p.y, rr + 8f * px, ringPaint)
            } else if (pulseOn && i == coins.size - 1 && activeMark in listOf("BLACK", "WHITE", "QUEEN")) {
                pulsePaint.strokeWidth = 4f * px
                canvas.drawCircle(c.p.x, c.p.y, rr + 9f * px, pulsePaint)
            }
        }
        for ((k, m) in markers) {
            val col = when {
                k == "S" -> Color.CYAN
                k.startsWith("P") -> Color.GREEN
                else -> Color.WHITE
            }
            val rr = 14f * px
            dotPaint.color = col
            canvas.drawCircle(m.x, m.y, rr, dotPaint)
            ringPaint.color = col
            ringPaint.strokeWidth = 5f * px
            canvas.drawCircle(m.x, m.y, 26f * px, ringPaint)
            labelPaint.textSize = 30f * px
            canvas.drawText(k, m.x + 30f * px, m.y + 10f * px, labelPaint)
            if (selMark == k) {
                ringPaint.color = Color.YELLOW
                ringPaint.strokeWidth = 4f * px
                canvas.drawCircle(m.x, m.y, 26f * px + 8f * px, ringPaint)
            }
        }
        fun path(pts: List<PointF>, paint: Paint, w: Float) {
            paint.strokeWidth = w * px
            for (i in 0 until pts.size - 1) {
                canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, paint)
            }
        }
        path(strikerPath, strikerLine, 6f)
        path(coinPath, coinLine, 6f)
        path(strikerAfter, afterPaint, 4f)
        path(comboPath, comboPaint, 6f)
        bestTarget?.let {
            ringPaint.color = Color.YELLOW
            ringPaint.strokeWidth = 5f * px
            canvas.drawCircle(it.x, it.y, 24f * px, ringPaint)
        }
        pocket?.let {
            ringPaint.color = Color.GREEN
            ringPaint.strokeWidth = 6f * px
            canvas.drawCircle(it.x, it.y, 34f * px, ringPaint)
        }
        canvas.restore()
    }
}
