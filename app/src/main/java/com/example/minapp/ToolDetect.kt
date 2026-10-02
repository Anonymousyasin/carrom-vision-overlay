package com.example.minapp

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Port of the tool's detector (detect_from_P): striker FIRST (so its rim
 * never becomes a fake coin), per-type CLEAN disc extraction, pocket refine
 * from the learned layout prior, game-rule caps. Runs on a bg thread;
 * bitmap input is the user's box already rectified to 600x600.
 */
object ToolDetect {

    const val WS = 300 // work size
    data class TCoin(val type: String, val x: Float, val y: Float, val r: Float, val score: Float)
    data class TStr(val x: Float, val y: Float, val r: Float, val score: Float)
    data class TPocket(val x: Float, val y: Float, val r: Float, val found: Boolean)
    data class Dets(val coins: List<TCoin>, val striker: TStr?, val pockets: List<TPocket>)

    private data class Off(val dx: Int, val dy: Int)

    private fun discOffsets(r: Float): Pair<List<Off>, List<Off>> {
        val R = r + 0.5f
        val disc = ArrayList<Off>()
        val ann = ArrayList<Off>()
        val lim = (r * 1.4f + 1).toInt()
        for (dy in -lim..lim) for (dx in -lim..lim) {
            val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (d <= R) disc.add(Off(dx, dy))
            else if (d <= r * 1.4f) ann.add(Off(dx, dy))
        }
        return disc to ann
    }

    fun detectBoard(crop600: Bitmap, m: ToolModel.Model): Dets {
        val small = Bitmap.createScaledBitmap(crop600, WS, WS, true)
        val px = IntArray(WS * WS)
        small.getPixels(px, 0, WS, 0, 0, WS, WS)
        val rgb = IntArray(px.size) { px[it] and 0x00FFFFFF }
        small.recycle()
        val post = ToolModel.posterior(rgb, WS, WS, m)
        val ci = m.order.indexOf("black")
        val wi = m.order.indexOf("white")
        val ri = m.order.indexOf("red")
        val si = m.order.indexOf("striker")
        val pi = m.order.indexOf("pocket")
        val f = WS / 600f
        val rCoin = m.rCoin * f
        val rStr = m.rStriker * f
        val rPok = m.rPocket * f

        // ---- pockets from layout prior + dark refine ----
        val pockets = ArrayList<TPocket>()
        val pm = post[pi]
        for ((nx, ny) in m.pocketsN) {
            var px0 = nx * WS
            var py0 = ny * WS
            var found = false
            val win = (rPok * 1.6f).toInt().coerceAtLeast(4)
            var mass = 0f
            var sx = 0f
            var sy = 0f
            val x0 = (px0 - win).toInt().coerceIn(0, WS - 1)
            val x1 = (px0 + win).toInt().coerceIn(0, WS - 1)
            val y0 = (py0 - win).toInt().coerceIn(0, WS - 1)
            val y1 = (py0 + win).toInt().coerceIn(0, WS - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val v = pm[y * WS + x]
                mass += v; sx += v * x; sy += v * y
            }
            val cov = mass / (Math.PI.toFloat() * rPok * rPok)
            if (cov >= m.thr["pocket"]!! && mass > 1e-6f) {
                px0 = sx / mass; py0 = sy / mass; found = true
            }
            pockets.add(TPocket(px0 / f, py0 / f, m.rPocket, found))
        }
        val noPocket = { x: Float, y: Float ->
            pockets.all { hypot((x - it.x).toDouble(), (y - it.y).toDouble()) > it.r * 1.05f }
        }

        // ---- striker first ----
        val sm = post[si].copyOf()
        // mask pockets out of striker map
        for (y in 0 until WS) for (x in 0 until WS) {
            if (!noPocket(x / f, y / f)) sm[y * WS + x] = 0f
        }
        var striker: TStr? = null
        var sx = 0f
        var sy = 0f
        var ss = -1f
        run {
            val (disc, ann) = discOffsets(rStr)
            var bx = 0
            var by = 0
            var bs = -1f
            for (y in 0 until WS) for (x in 0 until WS) {
                var sd = 0f
                var sa = 0f
                var nd = 0
                var na = 0
                for (o in disc) {
                    val xx = x + o.dx
                    val yy = y + o.dy
                    if (xx in 0 until WS && yy in 0 until WS) {
                        sd += sm[yy * WS + xx]; nd++
                    }
                }
                if (nd == 0) continue
                for (o in ann) {
                    val xx = x + o.dx
                    val yy = y + o.dy
                    if (xx in 0 until WS && yy in 0 until WS) {
                        sa += sm[yy * WS + xx]; na++
                    }
                }
                val resp = sd / nd - m.beta * (if (na > 0) sa / na else 0f)
                if (resp > bs) {
                    bs = resp; bx = x; by = y
                }
            }
            if (bs >= m.thr["striker"]!!) {
                sx = bx / f; sy = by / f; ss = bs
            }
        }
        if (ss >= 0f) {
            striker = TStr(sx, sy, m.rStriker, ss)
        }
        val noStr = { x: Float, y: Float ->
            striker == null || hypot((x - striker.x).toDouble(), (y - striker.y).toDouble()) > striker.r
        }

        // ---- coins per type, CLEAN extraction ----
        val found = ArrayList<TCoin>()
        val caps = mapOf("black" to 9, "white" to 9, "red" to 1)
        for ((type, idx) in listOf("black" to ci, "white" to wi, "red" to ri)) {
            val base = post[idx]
            val resp = FloatArray(WS * WS)
            val (disc, ann) = discOffsets(rCoin)
            for (y in 0 until WS) for (x in 0 until WS) {
                val v = base[y * WS + x]
                if (v < m.thr[type]!! * 0.5f) continue
                if (!noPocket(x / f, y / f) || !noStr(x / f, y / f)) continue
                var sd = 0f
                var sa = 0f
                var nd = 0
                var na = 0
                for (o in disc) {
                    val xx = x + o.dx
                    val yy = y + o.dy
                    if (xx in 0 until WS && yy in 0 until WS) {
                        sd += base[yy * WS + xx]; nd++
                    }
                }
                if (nd == 0) continue
                for (o in ann) {
                    val xx = x + o.dx
                    val yy = y + o.dy
                    if (xx in 0 until WS && yy in 0 until WS) {
                        sa += base[yy * WS + xx]; na++
                    }
                }
                resp[y * WS + x] = sd / nd - m.beta * (if (na > 0) sa / na else 0f)
            }
            var got = 0
            val maxn = caps[type] ?: 9
            var done = false
            repeat(maxn + 4) {
                if (done) return@repeat
                var bi = -1
                var bs = -1f
                for (i in resp.indices) if (resp[i] > bs) {
                    bs = resp[i]; bi = i
                }
                if (bi < 0 || bs < m.thr[type]!!) {
                    done = true
                    return@repeat
                }
                val cx = (bi % WS).toFloat()
                val cy = (bi / WS).toFloat()
                // centroid refine in disc
                var sw = 0f
                var scx = 0f
                var scy = 0f
                for (o in disc) {
                    val xx = (cx + o.dx).toInt()
                    val yy = (cy + o.dy).toInt()
                    if (xx in 0 until WS && yy in 0 until WS) {
                        val v = base[yy * WS + xx]
                        sw += v; scx += v * xx; scy += v * yy
                    }
                }
                val fx = if (sw > 1e-6f) scx / sw else cx
                val fy = if (sw > 1e-6f) scy / sw else cy
                // overlap prune vs accepted (0.75 rule like the tool)
                val ok = found.none {
                    hypot((fx / f - it.x).toDouble(), (fy / f - it.y).toDouble()) <
                        0.75f * (m.rCoin + m.rCoin)
                } && (striker?.let {
                    hypot((fx / f - it.x).toDouble(), (fy / f - it.y).toDouble()) > it.r
                } != false)
                if (ok && got < maxn) {
                    found.add(TCoin(type, fx / f, fy / f, m.rCoin, bs))
                    got++
                }
                // CLEAN: zero disc so touching coins split
                val zr = rCoin * 1.05f
                val zi = zr.toInt() + 1
                for (dy in -zi..zi) for (dx in -zi..zi) {
                    if (hypot(dx.toDouble(), dy.toDouble()) > zr) continue
                    val xx = (cx + dx).toInt()
                    val yy = (cy + dy).toInt()
                    if (xx in 0 until WS && yy in 0 until WS) resp[yy * WS + xx] = 0f
                }
            }
        }
        return Dets(found, striker, pockets)
    }

    /** Build the 600x600 board crop from the user's box (same as analyzer mapping). */
    fun crop600(src: Bitmap, box: android.graphics.RectF): Bitmap {
        val bx = box.left.toInt().coerceIn(0, src.width - 1)
        val by = box.top.toInt().coerceIn(0, src.height - 1)
        val bw = box.width().toInt().coerceIn(8, src.width - bx)
        val bh = box.height().toInt().coerceIn(8, src.height - by)
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(src, bx, by, bw, bh), 600, 600, true,
        )
    }
}
