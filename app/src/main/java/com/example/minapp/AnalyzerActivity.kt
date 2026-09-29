package com.example.minapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.hypot

/**
 * v4 accuracy flow: open app -> give it a screenshot -> drag box onto board ->
 * tap S (striker), paint coins (W/B/Q), tap P1/P2 (pockets) -> Best gives
 * the exact shot line. No hardcoded board, no AI.
 */
class AnalyzerActivity : AppCompatActivity() {

    private lateinit var view: AnalyzerView
    private lateinit var hint: TextView
    private lateinit var coinBtn: Button
    private lateinit var sideBtn: Button

    private var aimDeg = 0f
    private var manualAim = false
    private var playWhite = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri")
        if (uri == null) {
            toast("No image")
            finish()
            return
        }
        val bm = decode(uri) ?: run {
            toast("Cannot read image")
            finish()
            return
        }

        view = AnalyzerView(this).apply {
            bitmap = bm
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            onChanged = { refresh(null) }
        }
        hint = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
        }
        coinBtn = sbtn("Coin:W") {
            view.coinType = when (view.coinType) {
                "white" -> "black"
                "black" -> "red"
                else -> "white"
            }
            coinBtn.text = "Coin:${view.coinType.first().uppercase()}"
            view.activeMark = "COIN"
            refresh("Paint ${view.coinType} coins — tap each ${view.coinType} coin")
        }
        sideBtn = sbtn("I:White") {
            playWhite = !playWhite
            sideBtn.text = if (playWhite) "I:White" else "I:Black"
            manualAim = false
            refresh(null)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(view)
            addView(hint)
            addView(row(
                sbtn("Box") { view.activeMark = "BOX"; refresh("Drag box onto board, SE dot resizes") },
                sbtn("S") { view.activeMark = "S"; refresh("Tap YOUR striker") },
                coinBtn,
                sbtn("Undo") { if (view.coins.isNotEmpty()) view.coins.removeLast(); refresh(null) },
            ))
            addView(row(
                sbtn("P1") { view.activeMark = "P1"; refresh("Tap MY pocket") },
                sbtn("P2") { view.activeMark = "P2"; refresh("Tap OPP pocket") },
                sideBtn,
                sbtn("Best") { manualAim = false; refresh(null); announceBest() },
            ))
            addView(row(
                sbtn("Aim−") { aimDeg = (aimDeg - 2f + 360f) % 360f; manualAim = true; refresh(null) },
                sbtn("Aim+") { aimDeg = (aimDeg + 2f) % 360f; manualAim = true; refresh(null) },
                sbtn("Clear") { view.coins.clear(); view.bestTarget = null; refresh(null) },
                sbtn("X") { finish() },
            ))
        }
        setContentView(root)
        refresh("Drag Box onto the board, then S, coins, P1, P2")
    }

    private fun sbtn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setPadding(8, 4, 8, 4)
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        for (v in views) addView(v)
    }

    private fun to600(p: PointF): Pair<Float, Float> {
        val b = view.box
        return ((p.x - b.left) / b.width() * 600f) to
            ((p.y - b.top) / b.height() * 600f)
    }

    private fun toImage(p: Pair<Float, Float>): PointF {
        val b = view.box
        return PointF(
            b.left + p.first / 600f * b.width(),
            b.top + p.second / 600f * b.height(),
        )
    }

    private fun refresh(msg: String?) {
        if (msg != null) hint.text = msg
        val s = view.markers["S"]
        val coins600 = view.coins.map {
            val q = to600(it.p)
            Predictor.Coin(q.first, q.second, it.type)
        }
        if (s == null || coins600.isEmpty()) {
            view.strikerPath = emptyList()
            view.coinPath = emptyList()
            view.strikerAfter = emptyList()
            view.bestTarget = null
            view.pocket = null
            view.invalidate()
            if (msg == null) hint.text = "Need striker + at least 1 coin"
            return
        }
        val s600 = to600(s)
        val mine = if (playWhite) "white" else "black"
        val playable = coins600.filter { it.type == mine || it.type == "red" }
        val best = if (playable.isNotEmpty()) Predictor.bestShot(s600, playable, playWhite) else null
        if (best != null && !manualAim) aimDeg = best.angleDeg
        val rad = Math.toRadians((aimDeg - 90).toDouble()).toFloat()
        val res = Predictor.predictPath(s600, rad, coins600)
        view.strikerPath = res.strikerPath.map { toImage(it) }
        view.coinPath = res.coinPath.map { toImage(it) }
        view.strikerAfter = res.strikerAfter.map { toImage(it) }
        view.bestTarget = best?.let { toImage(it.targetX to it.targetY) }
        // pocket glow: any path end near P1/P2
        view.pocket = null
        val scale = view.box.width() / 600f
        val r = Predictor.POCKET_RADIUS * 1.5f * scale
        outer@ for (pk in listOfNotNull(view.markers["P1"], view.markers["P2"])) {
            for (pt in view.coinPath + view.strikerPath + view.strikerAfter) {
                if (hypot((pt.x - pk.x).toDouble(), (pt.y - pk.y).toDouble()) < r) {
                    view.pocket = pk
                    break@outer
                }
            }
        }
        view.invalidate()
        if (msg == null) {
            hint.text = best?.let {
                "BEST: ${it.reason} aim=${"%.0f".format(aimDeg)}°"
            } ?: "No clean pot — try Aim±"
        }
    }

    private fun announceBest() {
        val s = view.markers["S"] ?: return toast("Place S first")
        val coins600 = view.coins.map {
            val q = to600(it.p)
            Predictor.Coin(q.first, q.second, it.type)
        }
        val mine = if (playWhite) "white" else "black"
        val best = Predictor.bestShot(
            to600(s),
            coins600.filter { it.type == mine || it.type == "red" },
            playWhite,
        )
        toast(best?.let { "Best: ${it.reason}" } ?: "No clean pot found")
    }

    private fun decode(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxDim = bounds.outWidth.coerceAtLeast(bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
