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
 * v5 guided analyzer:
 * BOX -> BLACKS -> WHITES -> QUEEN -> STRIKER -> 4 POCKETS -> RESULT (Top-3).
 * Type-locked steps, pinch-zoom canvas, numbered markers, tapped-pocket physics.
 */
class AnalyzerActivity : AppCompatActivity() {

    private enum class Step {
        BOX, BLACK, WHITE, QUEEN, STRIKER, POCKETS, RESULT,
    }

    private lateinit var view: AnalyzerView
    private lateinit var stepBar: TextView
    private lateinit var hint: TextView
    private lateinit var cardsBox: LinearLayout
    private lateinit var sideBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var skipBtn: Button

    private var step = Step.BOX
    private var playWhite = true
    private var aimDeg = 0f
    private var manualAim = false
    private var selectedCard = 0
    private var lastShots: List<Predictor.Shot> = emptyList()

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
        stepBar = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1b3a4b"))
            setPadding(8, 12, 8, 12)
        }
        hint = TextView(this).apply {
            textSize = 13f; setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(8, 6, 8, 6)
        }
        cardsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sideBtn = sbtn("I:White") {
            playWhite = !playWhite
            sideBtn.text = if (playWhite) "I:White" else "I:Black"
            manualAim = false
            selectedCard = 0
            refresh(null)
        }
        nextBtn = sbtn("Next ›") { next() }
        skipBtn = sbtn("Skip") { next() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(stepBar)
            addView(view)
            addView(hint)
            addView(cardsBox)
            addView(row(
                sbtn("‹ Back") { back() },
                sbtn("Undo") { undoStep() },
                sbtn("Clear") { clearStep() },
                skipBtn,
                sbtn("Zoom 1:1") { view.resetZoom() },
                nextBtn,
            ))
            addView(row(
                sideBtn,
                sbtn("Aim−") { aimDeg = (aimDeg - 2f + 360f) % 360f; manualAim = true; refresh(null) },
                sbtn("Aim+") { aimDeg = (aimDeg + 2f) % 360f; manualAim = true; refresh(null) },
                sbtn("X") { finish() },
            ))
        }
        setContentView(root)
        applyStep()
    }

    // ---------- wizard ----------

    private fun stepIndex() = step.ordinal
    private fun totalSteps() = Step.values().size

    private fun applyStep() {
        view.activeMark = when (step) {
            Step.BOX -> "BOX"
            Step.BLACK -> "BLACK"
            Step.WHITE -> "WHITE"
            Step.QUEEN -> "QUEEN"
            Step.STRIKER -> "S"
            Step.POCKETS -> "POCKET"
            Step.RESULT -> null
        }
        val blacks = view.coins.count { it.type == "black" }
        val whites = view.coins.count { it.type == "white" }
        val queen = view.coins.any { it.type == "red" }
        val sPlaced = view.markers.containsKey("S")
        val pockets = view.markers.keys.count { it.startsWith("P") }
        stepBar.text = when (step) {
            Step.BOX -> "Step 1/7 · Board box"
            Step.BLACK -> "Step 2/7 · Black coins ($blacks)"
            Step.WHITE -> "Step 3/7 · White coins ($whites)"
            Step.QUEEN -> "Step 4/7 · Red queen (${if (queen) 1 else 0}/1)"
            Step.STRIKER -> "Step 5/7 · Your striker (${if (sPlaced) 1 else 0}/1)"
            Step.POCKETS -> "Step 6/7 · Pockets ($pockets/4)"
            Step.RESULT -> "Step 7/7 · Predictions"
        }
        skipBtn.visibility = if (step == Step.QUEEN) View.VISIBLE else View.GONE
        nextBtn.text = if (step == Step.POCKETS) "Results ›" else "Next ›"
        cardsBox.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
        sideBtn.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
        view.invalidate()
        refresh(
            when (step) {
                Step.BOX -> "Drag box onto the board — corner dot resizes. Pinch zooms."
                Step.BLACK -> "Tap every BLACK coin (zoom in for precision)"
                Step.WHITE -> "Tap every WHITE coin"
                Step.QUEEN -> "Tap the RED queen (Skip if already potted)"
                Step.STRIKER -> "Tap YOUR striker"
                Step.POCKETS -> "Tap all 4 pockets, any order"
                Step.RESULT -> null
            },
        )
    }

    private fun back() {
        if (step.ordinal > 0) {
            step = Step.values()[step.ordinal - 1]
            applyStep()
        }
    }

    private fun next() {
        when (step) {
            Step.STRIKER -> if (!view.markers.containsKey("S")) {
                toast("Place your striker first"); return
            }
            Step.POCKETS -> {
                val s = view.markers["S"]
                val n = view.coins.size
                val p = view.markers.keys.count { it.startsWith("P") }
                if (s == null || n == 0 || p == 0) {
                    toast("Need striker + coins + at least 1 pocket")
                    return
                }
            }
            else -> { }
        }
        if (step.ordinal < Step.RESULT.ordinal) {
            step = Step.values()[step.ordinal + 1]
            applyStep()
        }
    }

    private fun undoStep() {
        when (step) {
            Step.BLACK -> view.coins.indexOfLast { it.type == "black" }
                .takeIf { it >= 0 }?.let { view.coins.removeAt(it) }
            Step.WHITE -> view.coins.indexOfLast { it.type == "white" }
                .takeIf { it >= 0 }?.let { view.coins.removeAt(it) }
            Step.QUEEN -> view.coins.indexOfLast { it.type == "red" }
                .takeIf { it >= 0 }?.let { view.coins.removeAt(it) }
            Step.STRIKER -> view.markers.remove("S")
            Step.POCKETS -> {
                val last = view.markers.keys.filter { it.startsWith("P") }.maxOrNull()
                last?.let { view.markers.remove(it) }
            }
            else -> { }
        }
        refresh(null)
        view.invalidate()
        applyStep()
    }

    private fun clearStep() {
        when (step) {
            Step.BLACK -> view.coins.removeAll { it.type == "black" }
            Step.WHITE -> view.coins.removeAll { it.type == "white" }
            Step.QUEEN -> view.coins.removeAll { it.type == "red" }
            Step.STRIKER -> view.markers.remove("S")
            Step.POCKETS -> view.markers.keys.filter { it.startsWith("P") }
                .forEach { view.markers.remove(it) }
            else -> { }
        }
        refresh(null)
        view.invalidate()
        applyStep()
    }

    // ---------- mapping + physics ----------

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

    private fun pockets600(): List<Pair<Float, Float>> {
        val tapped = view.markers.keys.filter { it.startsWith("P") }.sorted()
            .map { to600(view.markers[it]!!) }
        return tapped.ifEmpty { Predictor.POCKETS }
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
            if (msg == null && step == Step.RESULT) hint.text = "Need striker + coins"
            return
        }
        val s600 = to600(s)
        val mine = if (playWhite) "white" else "black"
        val playable = coins600.filter { it.type == mine || it.type == "red" }
        val pkts = pockets600()
        lastShots = if (playable.isNotEmpty()) {
            Predictor.topShots(s600, playable, playWhite, pkts, 3)
        } else emptyList()
        val shot = lastShots.getOrNull(selectedCard) ?: lastShots.firstOrNull()
        if (shot != null && !manualAim) aimDeg = shot.angleDeg
        val rad = Math.toRadians((aimDeg - 90).toDouble()).toFloat()
        val res = Predictor.predictPath(s600, rad, coins600, 1, pkts)
        view.strikerPath = res.strikerPath.map { toImage(it) }
        view.coinPath = res.coinPath.map { toImage(it) }
        view.strikerAfter = res.strikerAfter.map { toImage(it) }
        view.bestTarget = shot?.let { toImage(it.targetX to it.targetY) }
        view.pocket = null
        val scale = view.box.width() / 600f
        val r = Predictor.POCKET_RADIUS * 1.5f * scale
        val tappedP = view.markers.keys.filter { it.startsWith("P") }.map { view.markers[it]!! }
        outer@ for (pk in tappedP) {
            for (pt in view.coinPath + view.strikerPath + view.strikerAfter) {
                if (hypot((pt.x - pk.x).toDouble(), (pt.y - pk.y).toDouble()) < r) {
                    view.pocket = pk
                    break@outer
                }
            }
        }
        view.invalidate()
        if (step == Step.RESULT) buildCards()
        if (msg == null && step != Step.RESULT) {
            hint.text = shot?.let {
                "Live: ${it.reason} aim=${"%.0f".format(aimDeg)}° (${Predictor.lastScan}) — Next ›"
            } ?: "No clean pot yet (${Predictor.lastScan}) — keep marking"
        }
    }

    private fun buildCards() {
        cardsBox.removeAllViews()
        if (lastShots.isEmpty()) {
            hint.text = "No clean pot — try Aim± or check marks"
            return
        }
        val sh = lastShots.getOrNull(selectedCard) ?: lastShots.first()
        hint.text = "#${selectedCard + 1}: ${sh.reason} ★${"%.2f".format(sh.score)} (${Predictor.lastScan})"
        lastShots.forEachIndexed { i, s ->
            val b = Button(this).apply {
                text = "#${i + 1} ${s.reason} ★${"%.2f".format(s.score)}"
                textSize = 13f
                setBackgroundColor(
                    if (i == selectedCard) Color.parseColor("#0e5a73")
                    else Color.parseColor("#333333"),
                )
                setTextColor(Color.WHITE)
                setOnClickListener {
                    selectedCard = i
                    manualAim = false
                    refresh(null)
                }
            }
            cardsBox.addView(b)
        }
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
