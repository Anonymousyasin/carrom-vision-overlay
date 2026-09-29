package com.example.minapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Screenshot analyzer view. All state lives in IMAGE pixels; the bitmap is
 * drawn fit-centered and touches are converted view->image.
 * Host activity owns the flow (box drag, marker taps, coin painting).
 */
class AnalyzerView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
    var box = RectF()
    var boxInitDone = false
    val markers = mutableMapOf<String, PointF>() // S, P1, P2 (image px)
    val coins = mutableListOf<CoinMark>() // tapped coins (image px)

    data class CoinMark(val p: PointF, val type: String)

    var activeMark: String? = null // BOX, S, P1, P2, COIN
    var coinType = "white"
    var strikerPath: List<PointF> = emptyList()
    var coinPath: List<PointF> = emptyList()
    var strikerAfter: List<PointF> = emptyList()
    var bestTarget: PointF? = null
    var pocket: PointF? = null

    var onChanged: (() -> Unit)? = null

    // view transform for current size
    private var scale = 1f
    private var offX = 0f
    private var offY = 0f

    private fun transform() {
        val bm = bitmap ?: return
        scale = minOf(width / bm.width.toFloat(), height / bm.height.toFloat()).coerceAtLeast(1e-6f)
        offX = (width - bm.width * scale) / 2f
        offY = (height - bm.height * scale) / 2f
    }

    fun toView(p: PointF): PointF = PointF(offX + p.x * scale, offY + p.y * scale)
    fun toImage(x: Float, y: Float): PointF = PointF((x - offX) / scale, (y - offY) / scale)

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
    private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    fun coinColor(t: String): Int = when (t) {
        "white" -> Color.parseColor("#f0e6d2")
        "black" -> Color.parseColor("#333333")
        "red" -> Color.parseColor("#e53e3e")
        else -> Color.CYAN
    }

    init {
        var dragMode = 0
        var lastIX = 0f
        var lastIY = 0f
        setOnTouchListener { _, e ->
            val bm = bitmap ?: return@setOnTouchListener false
            transform()
            val ip = toImage(e.x, e.y)
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastIX = ip.x; lastIY = ip.y
                    when (activeMark) {
                        "BOX" -> {
                            val tol = 60f / scale
                            dragMode = if (hypot(
                                    (ip.x - box.right).toDouble(),
                                    (ip.y - box.bottom).toDouble(),
                                ) < tol
                            ) 2 else 1
                            true
                        }
                        "S", "P1", "P2" -> {
                            markers[activeMark!!] = clamp(ip, bm)
                            activeMark = null
                            onChanged?.invoke()
                            invalidate()
                            true
                        }
                        "COIN" -> {
                            if (coins.size < 20) {
                                coins.add(CoinMark(clamp(ip, bm), coinType))
                                onChanged?.invoke()
                                invalidate()
                            }
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeMark != "BOX" || dragMode == 0) return@setOnTouchListener true
                    val dx = ip.x - lastIX
                    val dy = ip.y - lastIY
                    lastIX = ip.x; lastIY = ip.y
                    if (dragMode == 1) {
                        val w = box.width()
                        val nx = (box.left + dx).coerceIn(0f, bm.width - w)
                        val ny = (box.top + dy).coerceIn(0f, bm.height - w)
                        box = RectF(nx, ny, nx + w, ny + w)
                    } else {
                        val grow = (dx + dy) / 2f
                        val cx = box.centerX()
                        val cy = box.centerY()
                        val half = ((box.width() / 2f + grow)
                            .coerceIn(100f, bm.width.coerceAtMost(bm.height) / 2f))
                        box = RectF(cx - half, cy - half, cx + half, cy + half)
                    }
                    onChanged?.invoke()
                    invalidate()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    dragMode = 0
                    onChanged?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    private fun clamp(p: PointF, bm: Bitmap): PointF =
        PointF(p.x.coerceIn(0f, bm.width.toFloat()), p.y.coerceIn(0f, bm.height.toFloat()))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bm = bitmap ?: return
        transform()
        if (!boxInitDone) {
            val side = bm.width.coerceAtMost(bm.height) * 0.85f
            box = RectF(
                (bm.width - side) / 2f, (bm.height - side) / 2f,
                (bm.width + side) / 2f, (bm.height + side) / 2f,
            )
            boxInitDone = true
        }
        canvas.save()
        canvas.translate(offX, offY)
        canvas.scale(scale, scale)
        canvas.drawBitmap(bm, 0f, 0f, null)

        canvas.drawRect(box, if (activeMark == "BOX") boxActivePaint else boxPaint)
        for ((cx, cy) in listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom,
        )) canvas.drawCircle(cx, cy, 16f / scale, handlePaint)

        for (c in coins) {
            dotPaint.color = coinColor(c.type)
            canvas.drawCircle(c.p.x, c.p.y, 15f / scale, dotPaint)
            ringPaint.color = Color.BLACK
            ringPaint.strokeWidth = 3f / scale
            canvas.drawCircle(c.p.x, c.p.y, 15f / scale, ringPaint)
        }
        for ((k, m) in markers) {
            val col = when (k) {
                "S" -> Color.CYAN
                "P1" -> Color.GREEN
                "P2" -> Color.rgb(0, 200, 180)
                else -> Color.WHITE
            }
            dotPaint.color = col
            canvas.drawCircle(m.x, m.y, 14f / scale, dotPaint)
            ringPaint.color = col
            ringPaint.strokeWidth = 5f / scale
            canvas.drawCircle(m.x, m.y, 26f / scale, ringPaint)
            labelPaint.textSize = 30f / scale
            canvas.drawText(k, m.x + 30f / scale, m.y + 10f / scale, labelPaint)
        }
        fun path(pts: List<PointF>, paint: Paint) {
            for (i in 0 until pts.size - 1) {
                canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, paint)
            }
        }
        strikerLine.strokeWidth = 6f / scale
        coinLine.strokeWidth = 6f / scale
        afterPaint.strokeWidth = 4f / scale
        path(strikerPath, strikerLine)
        path(coinPath, coinLine)
        path(strikerAfter, afterPaint)
        bestTarget?.let {
            ringPaint.color = Color.YELLOW
            ringPaint.strokeWidth = 5f / scale
            canvas.drawCircle(it.x, it.y, 24f / scale, ringPaint)
        }
        pocket?.let {
            ringPaint.color = Color.GREEN
            ringPaint.strokeWidth = 6f / scale
            canvas.drawCircle(it.x, it.y, 34f / scale, ringPaint)
        }
        canvas.restore()
    }
}
