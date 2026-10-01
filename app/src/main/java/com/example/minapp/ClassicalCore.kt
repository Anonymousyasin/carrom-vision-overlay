package com.example.minapp

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Training-engine v1 brain: classical blob detector, zero dependencies.
 * Finds coins + queen inside the user's box and returns each with a
 * confidence. The wizard auto-accepts high-confidence marks and asks
 * about the rest; corrections become TuneRunner training signal.
 */
object ClassicalCore {

    data class Mark(
        val x: Float, // image px in SOURCE bitmap coords
        val y: Float,
        val r: Float, // image px radius
        val type: String, // black | white | red
        val conf: Float, // 0..1
    )

    data class Params(
        val woodTol: Float = 48f, // RGB distance from board color = foreground
        val fillLo: Float = 0.58f, // bbox fill-ratio band for discs
        val fillHi: Float = 0.96f,
        val rLoK: Float = 0.55f, // radius band vs expected coin radius
        val rHiK: Float = 1.5f,
        val redRG: Float = 55f, // R minus max(G,B) for queen red
        val blackV: Float = 95f, // brightness below = black
        val whiteS: Float = 90f, // saturation below + bright = white
    ) {
        fun toList() = listOf(woodTol, fillLo, fillHi, rLoK, rHiK, redRG, blackV, whiteS)

        companion object {
            fun fromList(v: List<Float>) = Params(
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7],
            )

            /** Random neighbor for tuner hill-climbing. */
            fun jitter(p: Params, rnd: kotlin.random.Random): Params {
                fun j(v: Float, lo: Float, hi: Float, amt: Float): Float {
                    return (v + (rnd.nextFloat() * 2 - 1) * amt).coerceIn(lo, hi)
                }
                return Params(
                    j(p.woodTol, 25f, 90f, 9f),
                    j(p.fillLo, 0.45f, 0.7f, 0.05f),
                    j(p.fillHi, 0.85f, 1f, 0.04f),
                    j(p.rLoK, 0.4f, 0.7f, 0.07f),
                    j(p.rHiK, 1.2f, 1.8f, 0.1f),
                    j(p.redRG, 35f, 80f, 8f),
                    j(p.blackV, 60f, 130f, 10f),
                    j(p.whiteS, 50f, 130f, 10f),
                )
            }
        }
    }

    /** Detect inside [box] (source-bitmap px). Returns marks in same coords. */
    fun detect(src: Bitmap, box: RectF, p: Params): List<Mark> {
        if (box.width() < 8f || box.height() < 8f) return emptyList()
        val bx = box.left.toInt().coerceIn(0, src.width - 1)
        val by = box.top.toInt().coerceIn(0, src.height - 1)
        val bw = box.width().toInt().coerceIn(8, src.width - bx)
        val bh = box.height().toInt().coerceIn(8, src.height - by)
        // work at ~160px for speed
        val scale = 160f / bw.coerceAtLeast(bh).toFloat()
        val w = (bw * scale).toInt().coerceAtLeast(16)
        val h = (bh * scale).toInt().coerceAtLeast(16)
        val small = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(src, bx, by, bw, bh), w, h, true,
        )
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val inv = 1f / (bw.toFloat() / w) // small-px -> bitmap-px

        // board color = mean of border ring (edges are almost always wood)
        var br = 0L
        var bg = 0L
        var bb = 0L
        var bn = 0L
        for (x in 0 until w) {
            for (y in listOf(0, 1, h - 2, h - 1)) {
                val c = px[y * w + x]
                br += (c shr 16) and 0xFF
                bg += (c shr 8) and 0xFF
                bb += c and 0xFF
                bn++
            }
        }
        for (y in 0 until h) {
            for (x in listOf(0, 1, w - 2, w - 1)) {
                val c = px[y * w + x]
                br += (c shr 16) and 0xFF
                bg += (c shr 8) and 0xFF
                bb += c and 0xFF
                bn++
            }
        }
        val mr = br.toFloat() / bn
        val mg = bg.toFloat() / bn
        val mb = bb.toFloat() / bn

        // foreground mask
        val fg = BooleanArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val dr = ((c shr 16) and 0xFF) - mr
            val dg = ((c shr 8) and 0xFF) - mg
            val db = (c and 0xFF) - mb
            fg[i] = hypot(hypot(dr.toDouble(), dg.toDouble()), db.toDouble()).toFloat() > p.woodTol
        }

        // connected components (BFS, 4-neighbourhood)
        val seen = BooleanArray(w * h)
        val out = ArrayList<Mark>()
        val expectedR = w * 15f / 600f // coin radius at work scale
        val stack = IntArray(w * h)
        for (s0 in 0 until w * h) {
            if (!fg[s0] || seen[s0]) continue
            var sp = 0
            stack[sp++] = s0
            seen[s0] = true
            var minX = w
            var maxX = -1
            var minY = h
            var maxY = -1
            var count = 0
            var sr = 0L
            var sg = 0L
            var sb = 0L
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                count++
                val c = px[i]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
                if (x > 0 && fg[i - 1] && !seen[i - 1]) {
                    seen[i - 1] = true; stack[sp++] = i - 1
                }
                if (x < w - 1 && fg[i + 1] && !seen[i + 1]) {
                    seen[i + 1] = true; stack[sp++] = i + 1
                }
                if (y > 0 && fg[i - w] && !seen[i - w]) {
                    seen[i - w] = true; stack[sp++] = i - w
                }
                if (y < h - 1 && fg[i + w] && !seen[i + w]) {
                    seen[i + w] = true; stack[sp++] = i + w
                }
            }
            val bw2 = (maxX - minX + 1).toFloat()
            val bh2 = (maxY - minY + 1).toFloat()
            if (bw2 < 3f || bh2 < 3f) continue
            val square = minOf(bw2, bh2) / maxOf(bw2, bh2)
            if (square < 0.72f) continue // not roundish (lines, cushions)
            val fill = count / (bw2 * bh2)
            if (fill < p.fillLo || fill > p.fillHi) continue
            val rSmall = sqrt(count / Math.PI).toFloat()
            if (rSmall < expectedR * p.rLoK || rSmall > expectedR * p.rHiK) continue
            val ar = sr.toFloat() / count
            val ag = sg.toFloat() / count
            val ab = sb.toFloat() / count
            val bright = (ar + ag + ab) / 3f
            val sat = maxOf(abs(ar - ag), abs(ar - ab), abs(ag - ab))
            val (type, purity) = when {
                ar - maxOf(ag, ab) > p.redRG -> "red" to ((ar - maxOf(ag, ab)) / 120f).coerceIn(0f, 1f)
                bright < p.blackV -> "black" to ((p.blackV - bright) / p.blackV).coerceIn(0f, 1f)
                sat < p.whiteS -> "white" to (1f - sat / p.whiteS).coerceIn(0f, 1f)
                else -> "" to 0f
            }
            if (type.isEmpty()) continue
            // confidence: size match + fill closeness to disc + color purity
            val sizeMatch = 1f - ((rSmall - expectedR) / expectedR).let {
                kotlin.math.abs(it).coerceIn(0f, 1f)
            }
            val fillMatch = 1f - kotlin.math.abs(fill - 0.785f) / 0.4f
            val conf = (0.45f * sizeMatch + 0.25f * fillMatch.coerceIn(0f, 1f) + 0.3f * purity)
                .coerceIn(0f, 1f)
            val cx = (minX + maxX) / 2f
            val cy = (minY + maxY) / 2f
            out.add(
                Mark(
                    bx + cx * inv, by + cy * inv,
                    rSmall * inv, type, conf,
                ),
            )
        }
        // biggest-first, cap 20 (matches wizard cap)
        return out.sortedByDescending { it.r }.take(20)
    }
}
