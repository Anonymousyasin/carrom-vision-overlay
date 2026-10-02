package com.example.minapp

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Port of the tool's learned colour model (carrom_model.json: Gaussian
 * mixtures per class + tuned hp + geometry priors). Computes the same
 * per-pixel softmax posteriors via Mahalanobis distance — pure arithmetic,
 * zero dependencies. The model file is produced by Termux training
 * (Carrom.py menu 2) and copied to Download/carrom_model.json.
 */
object ToolModel {

    data class Comp(val mu: FloatArray, val inv: FloatArray, val c: Float)

    data class Model(
        val classes: Map<String, List<Comp>>,
        val order: List<String>,
        val temp: Float,
        val bgBias: Float,
        val thr: Map<String, Float>,
        val beta: Float,
        val rCoin: Float,
        val rStriker: Float,
        val rPocket: Float,
        val pocketsN: List<Pair<Float, Float>>,
    )

    fun load(json: String): Model? {
        return try {
            val o = JSONObject(json)
            val order = listOf("black", "white", "red", "striker", "pocket", "board")
            val classes = HashMap<String, List<Comp>>()
            val jc = o.getJSONObject("classes")
            for (c in order) {
                val arr = jc.getJSONArray(c)
                val list = ArrayList<Comp>()
                for (i in 0 until arr.length()) {
                    val k = arr.getJSONObject(i)
                    val mu = FloatArray(3) { k.getJSONArray("mu").getDouble(it).toFloat() }
                    val cov = FloatArray(9)
                    val jc2 = k.getJSONArray("cov")
                    for (r in 0..2) for (cc in 0..2) cov[r * 3 + cc] = jc2.getJSONArray(r).getDouble(cc).toFloat()
                    val w = k.optDouble("w", k.optDouble("weight", 1.0)).toFloat()
                    val inv = invert3(cov) ?: continue
                    val det = det3(cov).coerceAtLeast(1e-9f)
                    list.add(Comp(mu, inv, (ln(w.coerceAtLeast(1e-6f)) - 0.5f * ln(det))))
                }
                if (list.isEmpty()) return null
                classes[c] = list
            }
            val hp = o.getJSONObject("hp")
            fun thr(name: String, d: Float) = hp.optDouble(name, d.toDouble()).toFloat()
            val thrMap = mapOf(
                "black" to thr("thr_black", 0.6f),
                "white" to thr("thr_white", 0.6f),
                "red" to thr("thr_red", 0.6f),
                "striker" to thr("thr_striker", 0.6f),
                "pocket" to thr("thr_pocket", 0.3f),
            )
            val geo = o.getJSONObject("geo")
            val pn = geo.getJSONArray("pockets_n")
            Model(
                classes, order,
                hp.optDouble("temp", 1.5).toFloat(),
                hp.optDouble("bg_bias", 0.0).toFloat(),
                thrMap,
                hp.optDouble("beta", 0.25).toFloat(),
                geo.optDouble("r_coin", 14.0).toFloat(),
                geo.optDouble("r_striker", 18.3).toFloat(),
                geo.optDouble("r_pocket", 22.9).toFloat(),
                List(pn.length()) { pn.getJSONArray(it).let { a -> a.getDouble(0).toFloat() to a.getDouble(1).toFloat() } },
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun det3(m: FloatArray): Float {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) -
            m[1] * (m[3] * m[8] - m[5] * m[6]) +
            m[2] * (m[3] * m[7] - m[4] * m[6])
    }

    private fun invert3(m: FloatArray): FloatArray? {
        val d = det3(m)
        if (d == 0f || !d.isFinite()) return null
        return floatArrayOf(
            (m[4] * m[8] - m[5] * m[7]) / d,
            (m[2] * m[7] - m[1] * m[8]) / d,
            (m[1] * m[5] - m[2] * m[4]) / d,
            (m[5] * m[6] - m[3] * m[8]) / d,
            (m[0] * m[8] - m[2] * m[6]) / d,
            (m[2] * m[3] - m[0] * m[5]) / d,
            (m[3] * m[7] - m[4] * m[6]) / d,
            (m[1] * m[6] - m[0] * m[7]) / d,
            (m[0] * m[4] - m[1] * m[3]) / d,
        )
    }

    /**
     * Softmax posteriors for every pixel. rgb = IntArray RGB packed.
     * Returns FloatArray [class][pixel] row-major over order.
     */
    fun posterior(rgb: IntArray, w: Int, h: Int, m: Model): Array<FloatArray> {
        val n = w * h
        val nc = m.order.size
        val logp = Array(nc) { FloatArray(n) { -1e9f } }
        for (ci in 0 until nc) {
            val comps = m.classes[m.order[ci]] ?: continue
            val acc = logp[ci]
            for (k in comps) {
                val mu0 = k.mu[0]
                val mu1 = k.mu[1]
                val mu2 = k.mu[2]
                val s = k.inv
                val cc = k.c
                for (i in 0 until n) {
                    val px = rgb[i]
                    val d0 = ((px shr 16) and 0xFF) - mu0
                    val d1 = ((px shr 8) and 0xFF) - mu1
                    val d2 = (px and 0xFF) - mu2
                    val q = d0 * (s[0] * d0 + s[1] * d1 + s[2] * d2) +
                        d1 * (s[3] * d0 + s[4] * d1 + s[5] * d2) +
                        d2 * (s[6] * d0 + s[7] * d1 + s[8] * d2)
                    val v = cc - 0.5f * q
                    if (v > acc[i]) acc[i] = v
                }
            }
        }
        val bi = m.order.indexOf("board")
        if (bi >= 0) {
            val acc = logp[bi]
            for (i in 0 until n) acc[i] += m.bgBias
        }
        // softmax with temperature
        val out = Array(nc) { FloatArray(n) }
        for (i in 0 until n) {
            var mx = Float.NEGATIVE_INFINITY
            for (ci in 0 until nc) {
                val v = logp[ci][i] / m.temp
                logp[ci][i] = v
                if (v > mx) mx = v
            }
            var sum = 0f
            for (ci in 0 until nc) {
                val e = exp((logp[ci][i] - mx).toDouble()).toFloat()
                out[ci][i] = e
                sum += e
            }
            if (sum > 0f) for (ci in 0 until nc) out[ci][i] /= sum
        }
        return out
    }

    /** Load order: app-private import first (scoped-storage safe),
     *  then legacy Download path (works pre-Android-10 / rooted). */
    fun loadFromApp(ctx: android.content.Context): Model? {
        try {
            val f = java.io.File(ctx.filesDir, "carrom_model.json")
            if (f.exists()) {
                load(f.readText())?.let { return it }
            }
        } catch (_: Exception) { }
        return try {
            load(java.io.File("/storage/emulated/0/Download/carrom_model.json").readText())
        } catch (_: Exception) {
            null
        }
    }

    /** Mahalanobis distance of an RGB pixel to the nearest component of a class. */
    fun maha(r: Float, g: Float, b: Float, comps: List<Comp>): Float {
        var best = Float.MAX_VALUE
        for (k in comps) {
            val d0 = r - k.mu[0]
            val d1 = g - k.mu[1]
            val d2 = b - k.mu[2]
            val s = k.inv
            val q = d0 * (s[0] * d0 + s[1] * d1 + s[2] * d2) +
                d1 * (s[3] * d0 + s[4] * d1 + s[5] * d2) +
                d2 * (s[6] * d0 + s[7] * d1 + s[8] * d2)
            if (q < best) best = q
        }
        return sqrt(best.coerceAtLeast(0f))
    }
}
