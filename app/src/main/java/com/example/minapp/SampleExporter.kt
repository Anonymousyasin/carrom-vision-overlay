package com.example.minapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * v8 training-sample exporter. One analyzer session -> schema-v1 sample:
 * full screenshot + 640px board crop + shot_NNNN.json (positions of ALL
 * pieces in full_px / crop_n / board600 frames, radii, occlusion, pockets,
 * game state, quality, provenance). See plan-doc schema v1.
 */
object SampleExporter {

    const val SCHEMA = 1
    const val CROP_OUT = 640

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "samples")

    fun count(ctx: Context): Int =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    data class InCoin(val p: PointF, val type: String)

    /**
     * @param bitmap decoded (possibly sampled) bitmap shown in the analyzer
     * @param imgScale full_image_px / bitmap_px (1.0 if decoded at full res)
     * @param box board box in BITMAP pixels
     * @param markers S + P1..P4 in BITMAP pixels
     * @param coins tapped coins in BITMAP pixels
     * @param best accepted prediction (optional auxiliary label)
     */
    fun export(
        ctx: Context,
        fullUri: Uri,
        bitmap: Bitmap,
        imgScale: Float,
        box: RectF,
        markers: Map<String, PointF>,
        coins: List<InCoin>,
        playWhite: Boolean,
        best: Predictor.Shot?,
    ): String? {
        try {
            if (box.width() < 1f || box.height() < 1f) return null
            val d = dir(ctx)
            d.mkdirs()
            val id = "shot_%04d".format(count(ctx) + 1)

            // ---- full image: copy bytes + dims + hash ----
            val fullBytes: ByteArray = ctx.contentResolver.openInputStream(fullUri)
                ?.use { it.readBytes() } ?: return null
            File(d, "$id-full.jpg").writeBytes(fullBytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size, bounds)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(fullBytes)
                .joinToString("") { "%02x".format(it) }

            // ---- crop 640 from the analyzed bitmap, mapped to full-frame box ----
            val fbox = RectF(
                box.left * imgScale, box.top * imgScale,
                box.right * imgScale, box.bottom * imgScale,
            )
            val crop = Bitmap.createScaledBitmap(
                Bitmap.createBitmap(
                    bitmap,
                    box.left.toInt().coerceIn(0, bitmap.width - 1),
                    box.top.toInt().coerceIn(0, bitmap.height - 1),
                    box.width().toInt().coerceAtMost(bitmap.width - box.left.toInt()),
                    box.height().toInt().coerceAtMost(bitmap.height - box.top.toInt()),
                ),
                CROP_OUT, CROP_OUT, true,
            )
            FileOutputStream(File(d, "$id.jpg")).use {
                crop.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            crop.recycle()

            // helpers: bitmap-px -> frames (bitmap is a uniform sample of full)
            fun to600(p: PointF) =
                ((p.x - box.left) / box.width() * 600f) to
                    ((p.y - box.top) / box.height() * 600f)
            // (p already in bitmap px; full = p * imgScale since bitmap is uniform sample)
            fun fullOf(p: PointF) = (p.x * imgScale) to (p.y * imgScale)
            fun cropN(p: PointF) =
                ((p.x - box.left) / box.width()) to ((p.y - box.top) / box.height())
            fun rOut(r600: Float) = r600 / 600f * CROP_OUT

            // ---- coins with occlusion ----
            val radii = mapOf("black" to 15f, "white" to 15f, "red" to 15f)
            val coinObjs = coins.mapIndexed { i, c ->
                val b600 = to600(c.p)
                val f = fullOf(c.p)
                val cn = cropN(c.p)
                var occ = 0
                for (o in coins) {
                    if (o === c) continue
                    val dd = hypot((o.p.x - c.p.x).toDouble(), (o.p.y - c.p.y).toDouble()).toFloat()
                    val r = radii[c.type] ?: 15f
                    if (dd < 2 * r) {
                        val pct = circleOverlapPct(r, r, dd)
                        if (pct > occ) occ = pct.toInt()
                    }
                }
                Triple(c, b600, occ)
            }
            val counters = mutableMapOf("black" to 0, "white" to 0, "red" to 0)
            val coinsArr = JSONArray()
            for ((c, b600, occ) in coinObjs) {
                val n = (counters[c.type] ?: 0) + 1
                counters[c.type] = n
                val cid = when (c.type) {
                    "black" -> "B$n"
                    "white" -> "W$n"
                    else -> "Q"
                }
                val f = fullOf(c.p)
                val cn = cropN(c.p)
                coinsArr.put(JSONObject()
                    .put("id", cid)
                    .put("type", if (c.type == "red") "red" else c.type)
                    .put("full_px", arr(f.first, f.second))
                    .put("crop_n", arr(cn.first, cn.second))
                    .put("board600", arr(b600.first, b600.second))
                    .put("r_px", rOut(radii[c.type] ?: 15f))
                    .put("occluded_pct", occ)
                    .put("cluster", occ > 0))
            }

            // ---- pockets: nearest ideal corner, unique ----
            val ideals = listOf(
                "TL" to (50f to 50f), "TR" to (550f to 50f),
                "BL" to (50f to 550f), "BR" to (550f to 550f),
            )
            val taken = mutableSetOf<String>()
            val pkArr = JSONArray()
            for (key in markers.keys.filter { it.startsWith("P") }.sorted()) {
                val m = markers[key]!!
                val b600 = to600(m)
                val corner = ideals.minByOrNull { (_, c) ->
                    hypot(
                        (b600.first - c.first).toDouble(),
                        (b600.second - c.second).toDouble(),
                    )
                }!!.first.let { firstChoice ->
                    if (taken.add(firstChoice)) firstChoice
                    else ideals.first { it.first !in taken }
                        .also { taken.add(it.first) }.first
                }
                val f = fullOf(m)
                val cn = cropN(m)
                pkArr.put(JSONObject()
                    .put("id", key)
                    .put("corner", corner)
                    .put("full_px", arr(f.first, f.second))
                    .put("crop_n", arr(cn.first, cn.second))
                    .put("r_px", rOut(25f))
                    .put("mine", false))
            }

            // ---- striker ----
            val sObj = markers["S"]?.let { m ->
                val b600 = to600(m)
                val f = fullOf(m)
                val cn = cropN(m)
                JSONObject()
                    .put("owner", "me")
                    .put("full_px", arr(f.first, f.second))
                    .put("crop_n", arr(cn.first, cn.second))
                    .put("board600", arr(b600.first, b600.second))
                    .put("r_px", rOut(20f))
                    .put("state", "placed")
            }

            val nBlack = counters["black"] ?: 0
            val nWhite = counters["white"] ?: 0
            val nRed = counters["red"] ?: 0
            val anyCluster = coinObjs.any { it.third > 0 }

            val root = JSONObject()
                .put("schema", SCHEMA)
                .put("id", id)
                .put("captured_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
                .put("app", "carrom-vision-overlay")
                .put("source", "manual")
                .put("device", JSONObject()
                    .put("model", Build.MODEL)
                    .put("os", "Android ${Build.VERSION.RELEASE}"))
                .put("game", JSONObject()
                    .put("mode", "carrom-pool")
                    .put("arena", "")
                    .put("theme", "")
                    .put("orientation", if (bounds.outWidth >= bounds.outHeight) "landscape" else "portrait")
                    .put("my_color", if (playWhite) "white" else "black")
                    .put("turn", "me")
                    .put("queen", if (nRed > 0) "on-board" else "potted")
                    .put("counts", JSONObject()
                        .put("black", nBlack).put("white", nWhite).put("red", nRed)))
                .put("full_image", JSONObject()
                    .put("w", bounds.outWidth).put("h", bounds.outHeight)
                    .put("sha1", sha1))
                .put("crop", JSONObject()
                    .put("x", fbox.left.toInt()).put("y", fbox.top.toInt())
                    .put("w", fbox.width().toInt()).put("h", fbox.height().toInt())
                    .put("out_w", CROP_OUT).put("out_h", CROP_OUT))
                .put("board", JSONObject()
                    .put("box_full_px", arr(fbox.left, fbox.top, fbox.right, fbox.bottom))
                    .put("scale_px_per_600", fbox.width() / 600f)
                    .put("pockets", pkArr))
                .put("coins", coinsArr)
            if (sObj != null) root.put("striker", sObj)
            root.put("quality", JSONObject()
                .put("blur", 0)
                .put("glare", false)
                .put("screenshot", true)
                .put("overlapping_cluster", anyCluster))
                .put("provenance", JSONObject()
                    .put("taps", "manual")
                    .put("model_assist", false)
                    .put("verified", true))
                .put("match", JSONObject.NULL)
            if (best != null) {
                root.put("prediction", JSONObject()
                    .put("target_board600", arr(best.targetX, best.targetY))
                    .put("pocket_board600", arr(best.pocketX, best.pocketY))
                    .put("angle_deg", best.angleDeg)
                    .put("score", best.score))
            }
            File(d, "$id.json").writeText(root.toString(1))
            return id
        } catch (_: Exception) {
            return null
        }
    }

    private fun arr(vararg v: Float) = JSONArray().apply { for (x in v) put(x) }

    /** % of circle r0 covered by circle r1 at center distance d. */
    private fun circleOverlapPct(r0: Float, r1: Float, d: Float): Double {
        if (d >= r0 + r1) return 0.0
        if (d <= Math.abs(r0 - r1)) return 100.0
        val d2 = d.toDouble()
        val a = r0 * r0 * acos(
            ((d2 + r0 * r0 - r1 * r1) / (2 * d2 * r0)).coerceIn(-1.0, 1.0),
        ) + r1 * r1 * acos(
            ((d2 + r1 * r1 - r0 * r0) / (2 * d2 * r1)).coerceIn(-1.0, 1.0),
        ) - 0.5 * sqrt(
            (-d2 + r0 + r1).coerceAtLeast(0.0) * (d2 + r0 + r1) *
                (d2 - r0 + r1).coerceAtLeast(0.0) * (d2 + r0 - r1).coerceAtLeast(0.0),
        )
        return a / (Math.PI * r0 * r0) * 100.0
    }
}
