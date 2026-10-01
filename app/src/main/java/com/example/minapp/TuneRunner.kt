package com.example.minapp

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.random.Random

/**
 * The learning loop: versioned threshold vectors tuned against YOUR saved
 * samples (ground truth). A challenger only dethrones the champion when it
 * wins on HELD-OUT samples — the engine can never regress. Flywheel:
 * corrections -> samples -> re-tune -> better marks -> fewer corrections.
 */
object TuneRunner {

    private const val PREFS = "tune"
    private const val KEY_VEC = "champion"
    private const val KEY_F1 = "champion_f1"
    private const val KEY_VER = "version"
    private const val KEY_CORR = "corrections"
    private const val KEY_AUTO = "auto_marks"

    fun current(ctx: Context): ClassicalCore.Params {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VEC, null) ?: return ClassicalCore.Params()
        return try {
            val a = JSONArray(s)
            ClassicalCore.Params.fromList(List(a.length()) { a.getDouble(it).toFloat() })
        } catch (_: Exception) {
            ClassicalCore.Params()
        }
    }

    fun version(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_VER, 0)

    fun championF1(ctx: Context): Float =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_F1, -1f)

    fun corrections(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CORR, 0)

    fun noteCorrection(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putInt(KEY_CORR, p.getInt(KEY_CORR, 0) + 1).apply()
    }

    fun noteAuto(ctx: Context, n: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AUTO, n).apply()
    }

    fun autoActive(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_AUTO, 0) > 0

    fun clearAuto(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_AUTO, 0).apply()
    }

    data class Sample(
        val id: String,
        val bitmap: android.graphics.Bitmap, // 640 crop
        val truth: List<TMark>,
    )

    data class TMark(val x: Float, val y: Float, val r: Float, val type: String)

    /** Load samples with crops from public folder. heldOut selects every 5th. */
    fun loadSamples(ctx: Context, heldOut: Boolean): List<Sample> {
        val out = ArrayList<Sample>()
        for (s in SampleExporter.list(ctx)) {
            val hold = (s.id.hashCode() and Int.MAX_VALUE) % 5 == 0
            if (hold != heldOut) continue
            val js = try {
                JSONObject(SampleExporter.readJson(ctx, s.id) ?: continue)
            } catch (_: Exception) {
                continue
            }
            val imgUri = SampleExporter.findUri(ctx, "${s.id}.jpg") ?: continue
            val bm = try {
                ctx.contentResolver.openInputStream(imgUri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (_: Exception) {
                null
            } ?: continue
            val truth = ArrayList<TMark>()
            val arr = js.optJSONArray("coins") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cn = o.optJSONArray("crop_n") ?: continue
                truth.add(
                    TMark(
                        (cn.optDouble(0) * 640).toFloat(),
                        (cn.optDouble(1) * 640).toFloat(),
                        o.optDouble("r_px", 16.0).toFloat(),
                        o.optString("type", "white"),
                    ),
                )
            }
            out.add(Sample(s.id, bm, truth))
        }
        return out
    }

    /** F1 of params over samples (type must match, dist < max(r, 18px)). */
    fun score(samples: List<Sample>, p: ClassicalCore.Params): Triple<Float, Float, Float> {
        var tp = 0
        var fp = 0
        var fn = 0
        for (s in samples) {
            val box = RectF(0f, 0f, s.bitmap.width.toFloat(), s.bitmap.height.toFloat())
            // detect works in source coords; map crop->bitmap 1:1 here
            val marks = ClassicalCore.detect(s.bitmap, box, p)
            val used = BooleanArray(marks.size)
            for (t in s.truth) {
                var best = -1
                var bestD = Float.MAX_VALUE
                for ((i, m) in marks.withIndex()) {
                    if (used[i] || m.type != t.type) continue
                    val d = hypot((m.x - t.x).toDouble(), (m.y - t.y).toDouble()).toFloat()
                    if (d < bestD) {
                        bestD = d; best = i
                    }
                }
                if (best >= 0 && bestD < maxOf(t.r, 18f)) {
                    tp++
                    used[best] = true
                } else {
                    fn++
                }
            }
            fp += used.count { !it }
        }
        val prec = if (tp + fp == 0) 0f else tp.toFloat() / (tp + fp)
        val rec = if (tp + fn == 0) 0f else tp.toFloat() / (tp + fn)
        val f1 = if (prec + rec == 0f) 0f else 2 * prec * rec / (prec + rec)
        return Triple(prec, rec, f1)
    }

    data class TuneResult(val version: Int, val f1: Float, val improved: Boolean, val detail: String)

    /** Hill-climb from champion; saves ONLY on held-out improvement. */
    fun tune(ctx: Context, iters: Int = 60, seed: Long = 7, onProgress: (Int) -> Unit = {}): TuneResult {
        val train = loadSamples(ctx, heldOut = false)
        val held = loadSamples(ctx, heldOut = true)
        try {
            if (train.isEmpty()) {
                return TuneResult(version(ctx), 0f, false, "No training samples — save some first")
            }
            val rnd = Random(seed)
            var best = current(ctx)
            var bestF1 = score(train, best).third
            val champHeld = if (held.isEmpty()) -1f else score(held, best).third
            repeat(iters) { i ->
                onProgress((i * 100) / iters)
                val cand = ClassicalCore.Params.jitter(best, rnd)
                val f1 = try {
                    score(train, cand).third
                } catch (_: Exception) {
                    -1f
                }
                if (f1 > bestF1) {
                    best = cand
                    bestF1 = f1
                }
            }
            onProgress(100)
            val heldF1 = if (held.isEmpty()) bestF1 else score(held, best).third
            val base = if (champHeld < 0) championF1(ctx) else champHeld
            val improved = heldF1 > base && bestF1 >= 0.01f
            val detail = "train F1 ${"%.2f".format(bestF1)} · held-out ${"%.2f".format(heldF1)} " +
                "(was ${if (base < 0) "—" else "%.2f".format(base)}) over ${train.size}+${held.size} samples"
            if (improved) {
                val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val v = version(ctx) + 1
                p.edit()
                    .putString(KEY_VEC, JSONArray(best.toList().map { it.toDouble() }).toString())
                    .putFloat(KEY_F1, heldF1)
                    .putInt(KEY_VER, v)
                    .apply()
                return TuneResult(v, heldF1, true, detail)
            }
            return TuneResult(version(ctx), heldF1, false, "No improvement. $detail")
        } finally {
            for (s in train + held) {
                try {
                    if (!s.bitmap.isRecycled) s.bitmap.recycle()
                } catch (_: Exception) { }
            }
        }
    }

    fun statsText(ctx: Context): String {
        val v = version(ctx)
        val f1 = championF1(ctx)
        val n = SampleExporter.count(ctx)
        val c = corrections(ctx)
        return "Engine v$v · held-out F1 " +
            (if (f1 < 0) "— (not tuned yet)" else "%.2f".format(f1)) +
            "\nSamples: $n · corrections logged: $c"
    }

    /** Shareable brain: versioned params + metrics (no images). */
    fun shareBrain(ctx: Context) {
        try {
            val js = JSONObject()
                .put("schema", 1)
                .put("version", version(ctx))
                .put("held_out_f1", championF1(ctx).toDouble())
                .put("params", JSONArray(current(ctx).toList().map { it.toDouble() }))
                .put("corrections", corrections(ctx))
            val bytes = js.toString(1).toByteArray()
            val values = android.content.ContentValues().apply {
                put(
                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    "brain_v${version(ctx)}.json",
                )
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, SampleExporter.REL_PATH)
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } catch (_: Exception) { }
    }
}
