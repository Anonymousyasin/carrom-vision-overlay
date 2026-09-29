package com.example.minapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.hypot

/**
 * Screen-space table: board box + markers + aim lines.
 * Coordinates are raw screen pixels; service maps Predictor 600-space into [box].
 */
class TableView(context: Context) : View(context) {

    var box = RectF(100f, 100f, 900f, 900f)
    var markers: Map<String, PointF> = emptyMap()
    var strikerPath: List<PointF> = emptyList()
    var coinPath: List<PointF> = emptyList()
    var pocket: PointF? = null
    var boxAdjust = false
    var sideMe = true

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
    private val dotPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val labelPaint = Paint().apply {
        color = Color.WHITE; textSize = 34f; isAntiAlias = true
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    fun colorOf(key: String): Int = when (key) {
        "S1" -> Color.CYAN
        "S2" -> Color.MAGENTA
        "T" -> Color.YELLOW
        "P1" -> Color.GREEN
        "P2" -> Color.rgb(0, 200, 180)
        else -> Color.WHITE
    }

    fun handleAt(x: Float, y: Float): Boolean =
        hypot((x - box.right).toDouble(), (y - box.bottom).toDouble()) < 80.0

    fun insideBox(x: Float, y: Float): Boolean = box.contains(x, y)

    fun clampToBox(p: PointF): PointF =
        PointF(p.x.coerceIn(box.left, box.right), p.y.coerceIn(box.top, box.bottom))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(box, if (boxAdjust) boxActivePaint else boxPaint)
        // corner handles
        for ((cx, cy) in listOf(
            box.left to box.top, box.right to box.top,
            box.left to box.bottom, box.right to box.bottom,
        )) canvas.drawCircle(cx, cy, 18f, handlePaint)

        for ((k, m) in markers) {
            val c = colorOf(k)
            dotPaint.color = c
            canvas.drawCircle(m.x, m.y, 15f, dotPaint)
            ringPaint.color = c
            canvas.drawCircle(m.x, m.y, 26f, ringPaint)
            canvas.drawText(k, m.x + 32f, m.y + 12f, labelPaint)
        }
        for (i in 0 until strikerPath.size - 1) {
            canvas.drawLine(
                strikerPath[i].x, strikerPath[i].y,
                strikerPath[i + 1].x, strikerPath[i + 1].y, strikerLine,
            )
        }
        for (i in 0 until coinPath.size - 1) {
            canvas.drawLine(
                coinPath[i].x, coinPath[i].y,
                coinPath[i + 1].x, coinPath[i + 1].y, coinLine,
            )
        }
        pocket?.let {
            ringPaint.color = Color.GREEN
            canvas.drawCircle(it.x, it.y, 40f, ringPaint)
        }
    }
}
