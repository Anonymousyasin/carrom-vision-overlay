package com.example.minapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** Draws board + prediction lines. Port of web PredictionOverlay to Canvas. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var striker: Pair<Float, Float> = 460f to 360f
    var coins: List<Predictor.Coin> = emptyList()
    var prediction: Predictor.Prediction? = null
    /** Board scale 0.7–1.0, tuned live with Zoom slider so lines sit on the real board. */
    var boardZoom: Float = 1f

    private val boardPaint = Paint().apply { color = Color.parseColor("#c49a6c"); style = Paint.Style.FILL }
    private val pocketPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }
    private val pocketGlow = Paint().apply {
        color = Color.argb(180, 74, 222, 128); style = Paint.Style.STROKE; strokeWidth = 8f
    }
    private val strikerPaint = Paint().apply { color = Color.parseColor("#a855f7"); style = Paint.Style.FILL }
    private val strikerLine = Paint().apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f); isAntiAlias = true
    }
    private val coinLine = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true
    }
    private val bestPaint = Paint().apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true
    }

    fun update(s: Pair<Float, Float>, c: List<Predictor.Coin>, p: Predictor.Prediction) {
        striker = s; coins = c; prediction = p
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // fit 600-space into view, centered (overlay is fullscreen over the game)
        val scale = minOf(width / 600f, height / 600f) * boardZoom
        canvas.save()
        canvas.translate((width - 600f * scale) / 2f, (height - 600f * scale) / 2f)
        canvas.scale(scale, scale)

        canvas.drawRect(0f, 0f, 600f, 600f, boardPaint)
        for ((px, py) in Predictor.POCKETS) canvas.drawCircle(px, py, 25f, pocketPaint)

        val coinPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        for (c in coins) {
            coinPaint.color = when (c.type) {
                "white" -> Color.parseColor("#f0e6d2")
                "black" -> Color.parseColor("#333333")
                "red" -> Color.parseColor("#e53e3e")
                else -> Color.CYAN
            }
            canvas.drawCircle(c.x, c.y, 15f, coinPaint)
        }
        canvas.drawCircle(striker.first, striker.second, 20f, strikerPaint)

        val pred = prediction ?: run { canvas.restore(); return }
        // striker path dashed cyan
        for (i in 0 until pred.strikerPath.size - 1) {
            val (x1, y1) = pred.strikerPath[i]
            val (x2, y2) = pred.strikerPath[i + 1]
            canvas.drawLine(x1, y1, x2, y2, strikerLine)
        }
        // coin path white
        for (i in 0 until pred.coinPath.size - 1) {
            val (x1, y1) = pred.coinPath[i]
            val (x2, y2) = pred.coinPath[i + 1]
            canvas.drawLine(x1, y1, x2, y2, coinLine)
        }
        pred.best?.let {
            canvas.drawCircle(it.targetX, it.targetY, 22f, bestPaint)
        }
        pred.pocket?.let { (px, py) ->
            canvas.drawCircle(px, py, 30f, pocketGlow)
        }
        canvas.restore()
    }
}
