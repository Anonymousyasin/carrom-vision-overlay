package com.example.minapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
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
    private lateinit var stepProgress: android.widget.ProgressBar
    private lateinit var hint: TextView
    private lateinit var cardsBox: LinearLayout
    private lateinit var cardsScroll: android.widget.ScrollView
    private lateinit var sideBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var skipBtn: Button

    private var step = Step.BOX
    private var playWhite = true
    private var selectedCard = 0
    private var lastShots: List<Predictor.Shot> = emptyList()
    /** Data-gen mode: mark + save only, no search/AI UI. */
    private var dataMode = false
    private lateinit var whyBtn: Button
    /** Parallel to lastShots: true = your color, false = opponent threat. */
    private var lastMine: List<Boolean> = emptyList()
    /** Full ULTRA results (traces) parallel to lastShots. */
    private var lastFound: List<Search.Found> = emptyList()
    private var searchGen = 0
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
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(24, 14, 24, 14)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 48f
                setColor(Color.parseColor("#0e5a73"))
            }
        }
        val stepWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 4)
            addView(stepBar)
            addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = Step.values().size
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }.also { stepProgress = it })
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
        val cardsScroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * resources.displayMetrics.density).toInt(),
            )
            addView(cardsBox)
            visibility = View.GONE
        }
        this.cardsScroll = cardsScroll
        sideBtn = sbtn("I:White") {
            playWhite = !playWhite
            sideBtn.text = if (playWhite) "I:White" else "I:Black"
            selectedCard = 0
            refresh(null)
        }
        nextBtn = sbtn("Next ›") { next() }
        skipBtn = sbtn("Skip") { next() }
        whyBtn = sbtn("🔍 Why") { showWhy() }

        saveBtn = sbtn("💾 Save") { saveSample() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(stepWrap)
            addView(view)
            addView(hint)
            addView(cardsScroll)
            addView(row(
                sbtn("‹ Back") { back() },
                sbtn("Undo") { undoStep() },
                sbtn("Clear") { clearStep() },
                skipBtn,
                sbtn("Zoom 1:1") { view.resetZoom() },
                sbtn("🔍 Tool") { toolAuto() },
                sbtn("🔍 Why") { showWhy() },
                nextBtn,
            ))
            addView(row(
                sideBtn,
                saveBtn,
                sbtn("X") { finish() },
            ))
        }
        setContentView(root)
        dataMode = intent.getStringExtra("mode") == "data"
        // saved color from the menu ("I play White/Black"); RESULT toggle still overrides
        playWhite = getSharedPreferences("cv_prefs", MODE_PRIVATE).getBoolean("playWhite", true)
        sideBtn.text = if (playWhite) "I:White" else "I:Black"
        applyStep()
    }

    // ---------- wizard ----------

    private fun stepIndex() = step.ordinal
    private fun totalSteps() = Step.values().size

    private fun applyStep() {
        stepProgress.progress = step.ordinal + 1
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
            Step.RESULT -> if (dataMode) "Review + Save" else "Step 7/7 · Predictions"
        }
        skipBtn.visibility = if (step == Step.QUEEN) View.VISIBLE else View.GONE
        nextBtn.text = if (step == Step.POCKETS) "Review ›" else "Next ›"
        whyBtn.visibility = if (dataMode) View.GONE else View.VISIBLE
        cardsScroll.visibility = if (step == Step.RESULT) View.VISIBLE else View.GONE
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
            searchGen++ // invalidate in-flight searches
            view.strikerPath = emptyList()
            view.coinPath = emptyList()
            view.strikerAfter = emptyList()
            view.comboPath = emptyList()
            view.bestTarget = null
            view.pocket = null
            view.invalidate()
            if (msg == null && step == Step.RESULT) hint.text = "Need striker + coins"
            return
        }
        if (dataMode) {
            // mark + save only: no search, no lines — just a live count
            view.strikerPath = emptyList()
            view.coinPath = emptyList()
            view.strikerAfter = emptyList()
            view.comboPath = emptyList()
            view.bestTarget = null
            view.pocket = null
            view.invalidate()
            if (msg == null) {
                val p = view.markers.keys.count { it.startsWith("P") }
                hint.text = if (step == Step.RESULT) {
                    "Review: ${view.coins.size} coins · S ✓ · $p/4 pockets → 💾 Save"
                } else {
                    "${view.coins.size} coins marked — Next ›"
                }
            }
            return
        }
        // markers draw instantly; ULTRA search runs off the UI thread
        if (msg == null) hint.text = "ULTRA searching…"
        view.invalidate()
        val gen = ++searchGen
        val s600 = to600(s)
        val pkts = pockets600()
        val pktR = pocketRadii600()
        val sr = strikerR600()
        val wantWhite = playWhite
        Thread {
            val mine = Search.fast(s600, sr, coins600, wantWhite, pkts, pktR, 900)
            val opp = Search.fast(s600, sr, coins600, !wantWhite, pkts, pktR, 500)
            fun keyOf(f: Search.Found) = "${f.targetIdx}:${f.pocketIdx}"
            val seen = mutableSetOf<String>()
            val combined = ArrayList<Search.Found>()
            val combinedMine = ArrayList<Boolean>()
            for ((pool, isMine) in listOf(mine to true, opp to false)) {
                for (f in pool) {
                    if (combined.size >= 8) break
                    if (seen.add(keyOf(f))) {
                        combined.add(f)
                        combinedMine.add(isMine)
                    }
                }
            }
            runOnUiThread {
                if (gen != searchGen) return@runOnUiThread
                lastFound = combined
                lastMine = combinedMine
                lastShots = combined.map { f ->
                    val c = coins600[f.targetIdx]
                    val pk = pkts[f.pocketIdx]
                    Predictor.Shot(
                        c.x, c.y, c.x, c.y,
                        f.angleDeg, f.angleRad,
                        pk.first, pk.second,
                        f.score, "${c.type} ${f.kind}", f.kind,
                    )
                }
                if (selectedCard >= lastShots.size) selectedCard = 0
                drawSelected()
            }
        }.start()
    }

    /** Draw the selected card's SIMULATED traces (real paths, never raycasts). */
    private fun drawSelected() {
        val f = lastFound.getOrNull(selectedCard)
        if (f == null) {
            view.strikerPath = emptyList()
            view.coinPath = emptyList()
            view.strikerAfter = emptyList()
            view.comboPath = emptyList()
            view.bestTarget = null
            view.pocket = null
            view.invalidate()
            if (step == Step.RESULT) {
                buildCards()
                hint.text = "No clean pot (${Search.lastStats}) — check marks"
            }
            return
        }
        val o = f.outcome
        view.strikerPath = o.traceStriker.map { toImage(it) }
        view.coinPath = (o.traces[f.targetIdx] ?: emptyList()).map { toImage(it) }
        view.comboPath = if (f.viaIdx >= 0) {
            (o.traces[f.viaIdx] ?: emptyList()).map { toImage(it) }
        } else emptyList()
        view.strikerAfter = emptyList() // deflection lives inside traceStriker
        val tc = toImage(coins600of(f.targetIdx))
        view.bestTarget = tc
        view.pocket = pockets600().getOrNull(f.pocketIdx)?.let { toImage(it) }
        view.invalidate()
        if (step == Step.RESULT) buildCards()
        val tag = if (lastMine.getOrElse(selectedCard) { true }) "YOU" else "OPP"
        hint.text = "#${selectedCard + 1} [$tag][${f.kind}]: ${coinLabelOf(f.targetIdx)} → ${pocketNameOf(f.pocketIdx)} ★${"%.0f".format(f.score)} (${Search.lastStats})"
    }

    private fun coins600of(idx: Int): Pair<Float, Float> {
        val c = view.coins[idx]
        return to600(c.p)
    }

    private fun coinLabelOf(idx: Int): String {
        val t = view.coins[idx].type
        var n = 0
        for (i in 0..idx) if (view.coins[i].type == t) n++
        return when (t) {
            "black" -> "B$n"
            "white" -> "W$n"
            else -> "Q"
        }
    }

    private fun pocketNameOf(pi: Int): String {
        val p = pockets600().getOrNull(pi) ?: return "P?"
        val ideals = listOf("TL" to (50f to 50f), "TR" to (550f to 50f), "BL" to (50f to 550f), "BR" to (550f to 550f))
        return ideals.minByOrNull { (_, c) ->
            hypot(
                (p.first - c.first).toDouble(),
                (p.second - c.second).toDouble(),
            )
        }?.first ?: "P?"
    }

    private fun showWhy() {
        val lines = Search.lastNotes
        android.app.AlertDialog.Builder(this)
            .setTitle("ULTRA verdicts (${lines.size})")
            .setMessage(if (lines.isEmpty()) "Nothing searched yet — mark pieces first." else lines.joinToString("\n"))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun buildCards() {
        cardsBox.removeAllViews()
        if (lastShots.isEmpty()) {
            hint.text = "No clean pot (${Search.lastStats}) — check marks"
            return
        }
        lastShots.forEachIndexed { i, s ->
            val f = lastFound.getOrNull(i)
            val tag = if (lastMine.getOrElse(i) { true }) "YOU" else "OPP"
            val kind = f?.kind ?: s.kind
            val label = if (f != null) {
                "${coinLabelOf(f.targetIdx)} → ${pocketNameOf(f.pocketIdx)}"
            } else s.reason
            val b = Button(this).apply {
                text = "#${i + 1} [$tag][$kind] $label ★${"%.0f".format(s.score)}"
                textSize = 13f
                setBackgroundColor(
                    when {
                        i == selectedCard -> Color.parseColor("#0e5a73")
                        kind == "BANK" -> Color.parseColor("#5a4a12")
                        kind == "COMBO" -> Color.parseColor("#4a235a")
                        !lastMine.getOrElse(i) { true } -> Color.parseColor("#5a2323")
                        else -> Color.parseColor("#333333")
                    },
                )
                setTextColor(Color.WHITE)
                setOnClickListener {
                    selectedCard = i
                    drawSelected()
                }
            }
            cardsBox.addView(b)
        }
    }

    /** Tool-model auto-mark: learned colour mixtures drive detection.
     *  Model = Download/carrom_model.json (Termux training output). */
    private var toolModel: ToolModel.Model? = null

    private fun toolAuto() {
        val bm = view.bitmap ?: run {
            toast("No image")
            return
        }
        if (view.box.width() < 8f) {
            toast("Drag the Box onto the board first")
            return
        }
        if (toolModel == null) {
            toolModel = try {
                val txt = java.io.File("/storage/emulated/0/Download/carrom_model.json").readText()
                ToolModel.load(txt)
            } catch (_: Exception) {
                null
            }
        }
        val m = toolModel ?: run {
            toast("No model — train in Termux, copy carrom_model.json to Download")
            return
        }
        toast("Tool detecting…")
        refresh("Tool model detecting pieces…")
        Thread {
            val crop = ToolDetect.crop600(bm, view.box)
            val res = try {
                ToolDetect.detectBoard(crop, m)
            } catch (e: Exception) {
                runOnUiThread { toast("Detect failed: ${e.message}") }
                return@Thread
            } finally {
                try {
                    crop.recycle()
                } catch (_: Exception) { }
            }
            runOnUiThread {
                // map board600 (box space) back to bitmap pixels
                val b = view.box
                fun bx(x: Float) = b.left + x / 600f * b.width()
                fun by(y: Float) = b.top + y / 600f * b.height()
                fun br(r: Float) = r / 600f * b.width()
                view.coins.removeAll { it.type == "black" || it.type == "white" || it.type == "red" }
                for (c in res.coins) {
                    if (view.coins.size >= 20) break
                    view.coins.add(
                        AnalyzerView.CoinMark(PointF(bx(c.x), by(c.y)), c.type, br(c.r)).apply {
                            conf = 0.7f
                            touched = false // amber until you tap to confirm
                        },
                    )
                }
                res.striker?.let { s ->
                    view.markers["S"] = PointF(bx(s.x), by(s.y))
                    view.markRadii["S"] = br(s.r)
                    if (view.selMark == "S") view.selMark = null
                }
                val keys = listOf("P1", "P2", "P3", "P4")
                for ((i, p) in res.pockets.withIndex()) {
                    if (i >= keys.size) break
                    view.markers[keys[i]] = PointF(bx(p.x), by(p.y))
                    view.markRadii[keys[i]] = br(p.r)
                }
                view.selCoin = -1
                refresh(null)
                view.invalidate()
                toast("Tool: ${res.coins.size} coins, striker ${if (res.striker != null) "yes" else "NO"} — tap amber to confirm")
            }
        }.start()
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
                val err = SampleExporter.lastError
                toast(if (id != null) "Saved $id (${SampleExporter.count(this)} samples)" else "Save failed: $err")
            }
        }.start()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
