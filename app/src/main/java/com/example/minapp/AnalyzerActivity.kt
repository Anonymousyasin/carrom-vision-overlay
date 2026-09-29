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
import android.widget.HorizontalScrollView
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
    private var imageUri: Uri? = null
    private var fullW = 0
    private var fullH = 0
    private lateinit var saveBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getParcelableExtra<Uri>("uri")
        if (uri == null) {
            toast("No image")
            finish()
            return
        }
        imageUri = uri
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

        saveBtn = sbtn("💾 Save") { saveSample() }
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
                saveBtn,
                sbtn("X") { finish() },
            ))
        }
        setContentView(root)
        // saved color from the menu ("I play White/Black"); RESULT toggle still overrides
        playWhite = getSharedPreferences("cv_prefs", MODE_PRIVATE).getBoolean("playWhite", true)
        sideBtn.text = if (playWhite) "I:White" else "I:Black"
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
        saveBtn.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
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
                .takeIf { it >= 0 }?.let {
                    view.coins.removeAt(it)
                    if (view.selCoin == it) view.selCoin = -1
                }
            Step.WHITE -> view.coins.indexOfLast { it.type == "white" }
                .takeIf { it >= 0 }?.let {
                    view.coins.removeAt(it)
                    if (view.selCoin == it) view.selCoin = -1
                }
            Step.QUEEN -> view.coins.indexOfLast { it.type == "red" }
                .takeIf { it >= 0 }?.let {
                    view.coins.removeAt(it)
                    if (view.selCoin == it) view.selCoin = -1
                }
            Step.STRIKER -> {
                view.markers.remove("S")
                view.markRadii.remove("S")
                if (view.selMark == "S") view.selMark = null
            }
            Step.POCKETS -> {
                val last = view.markers.keys.filter { it.startsWith("P") }.maxOrNull()
                last?.let {
                    view.markers.remove(it)
                    view.markRadii.remove(it)
                    if (view.selMark == it) view.selMark = null
                }
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
            Step.STRIKER -> {
                view.markers.remove("S")
                view.markRadii.remove("S")
            }
            Step.POCKETS -> view.markers.keys.filter { it.startsWith("P") }
                .forEach {
                    view.markers.remove(it)
                    view.markRadii.remove(it)
                }
            else -> { }
        }
        view.selCoin = -1
        view.selMark = null
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

    private fun row(vararg views: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            for (v in views) addView(v)
        })
    }

    private fun to600(p: PointF): Pair<Float, Float> {
        val b = view.box
        return ((p.x - b.left) / b.width() * 600f) to
            ((p.y - b.top) / b.height() * 600f)
    }

    private fun r600(rImg: Float): Float = rImg / view.box.width() * 600f

    private fun strikerR600(): Float =
        view.markRadii["S"]?.let { r600(it) } ?: Predictor.STRIKER_RADIUS

    private fun pockets600(): List<Pair<Float, Float>> {
        val tapped = view.markers.keys.filter { it.startsWith("P") }.sorted()
            .map { to600(view.markers[it]!!) }
        return tapped.ifEmpty { Predictor.POCKETS }
    }

    private fun pocketRadii600(): List<Float>? {
        val keys = view.markers.keys.filter { it.startsWith("P") }.sorted()
        if (keys.isEmpty()) return null
        return keys.map { r600(view.markR(it)) }
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
        val coins600 = view.coins.mapIndexed { i, c ->
            val q = to600(c.p)
            Predictor.Coin(q.first, q.second, c.type, r600(view.coinR(i)))
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
        val pktR = pocketRadii600()
        val sr = strikerR600()
        lastShots = if (playable.isNotEmpty()) {
            Predictor.topShots(s600, playable, playWhite, pkts, 3, sr, pktR)
        } else emptyList()
        val shot = lastShots.getOrNull(selectedCard) ?: lastShots.firstOrNull()
        if (shot != null && !manualAim) aimDeg = shot.angleDeg
        val rad = Math.toRadians((aimDeg - 90).toDouble()).toFloat()
        val res = Predictor.predictPath(s600, rad, coins600, 1, pkts, sr, pktR)
        view.strikerPath = res.strikerPath.map { toImage(it) }
        view.coinPath = res.coinPath.map { toImage(it) }
        view.strikerAfter = res.strikerAfter.map { toImage(it) }
        view.bestTarget = shot?.let { toImage(it.targetX to it.targetY) }
        view.pocket = null
        val tappedP = view.markers.keys.filter { it.startsWith("P") }.sorted()
            .map { view.markers[it]!! to view.markR(it) }
        outer@ for ((pk, pr) in tappedP) {
            for (pt in view.coinPath + view.strikerPath + view.strikerAfter) {
                if (hypot((pt.x - pk.x).toDouble(), (pt.y - pk.y).toDouble()) < pr * 1.5f) {
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
            fullW = bounds.outWidth
            fullH = bounds.outHeight
            var sample = 1
            val maxDim = bounds.outWidth.coerceAtLeast(bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun saveSample() {
        val bm = view.bitmap
        val uri = imageUri
        if (bm == null || uri == null) {
            toast("No image")
            return
        }
        if (!view.markers.containsKey("S") || view.coins.isEmpty()) {
            toast("Mark striker + coins first")
            return
        }
        toast("Saving…")
        Thread {
            val scale = if (bm.width > 0 && fullW > 0) fullW.toFloat() / bm.width else 1f
            val id = SampleExporter.export(
                this, uri, bm, scale, view.box, view.markers.toMap(),
                view.markRadii.toMap(),
                view.coins.mapIndexed { i, c ->
                    SampleExporter.InCoin(PointF(c.p.x, c.p.y), c.type, view.coinR(i))
                },
                playWhite,
                lastShots.getOrNull(selectedCard),
            )
            runOnUiThread {
                toast(if (id != null) "Saved $id (${SampleExporter.count(this)} samples)" else "Save failed")
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
