package com.example.minapp

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * v9 training-sample exporter. Samples live in PUBLIC Download/CarromSamples/
 * via MediaStore (visible in Files/USB/share sheets — no more locked
 * Android/data path). One session -> schema-v1 JSON + full shot + 640 crop,
 * with TRUE tapped radii for every piece.
 */
object SampleExporter {

    const val SCHEMA = 1
    const val CROP_OUT = 640
    const val REL_PATH = "Download/CarromSamples/"

    data class InCoin(val p: PointF, val type: String, val rImg: Float)

    data class Sample(val id: String, val dateS: Long)

    /** Human-readable reason the last export failed ("" = no failure yet). */
    var lastError = ""
        private set

    private fun downloads(): Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    fun count(ctx: Context): Int {
        return try {
            ctx.contentResolver.query(
                downloads(),
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE 'shot_%.json'",
                null, null,
            )?.use { it.count } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun list(ctx: Context): List<Sample> {
        val out = mutableListOf<Sample>()
        try {
            ctx.contentResolver.query(
                downloads(),
                arrayOf(
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.DATE_ADDED,
                ),
                "${MediaStore.Downloads.DISPLAY_NAME} LIKE 'shot_%.json'",
                null,
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { c ->
                val ni = c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val di = c.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                while (c.moveToNext()) {
                    out.add(Sample(c.getString(ni).removeSuffix(".json"), c.getLong(di)))
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun readJson(ctx: Context, id: String): String? {
        return try {
            findUri(ctx, "$id.json")?.let { uri ->
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun delete(ctx: Context, id: String): Boolean {
        var ok = true
        for (name in listOf("$id.json", "$id.jpg", "$id-full.jpg")) {
            try {
                findUri(ctx, name)?.let { ctx.contentResolver.delete(it, null, null) }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }

    /** Share one sample (3 files) or all samples. */
    fun share(ctx: Context, id: String?) {
        try {
            val uris = ArrayList<Uri>()
            val names = if (id != null) {
                listOf("$id.json", "$id.jpg", "$id-full.jpg")
            } else {
                list(ctx).flatMap { listOf("${it.id}.json", "${it.id}.jpg", "${it.id}-full.jpg") }
            }
            for (n in names) findUri(ctx, n)?.let { uris.add(it) }
            if (uris.isEmpty()) return
            val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(i, "Share samples"))
        } catch (_: Exception) { }
    }

    private fun findUri(ctx: Context, name: String): Uri? {
        return try {
            ctx.contentResolver.query(
                downloads(),
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?",
                arrayOf(name), null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    ContentUris.withAppendedId(
                        downloads(),
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID)),
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun writePublic(ctx: Context, name: String, mime: String, bytes: ByteArray): Boolean {
        return try {
            // replace existing
            try {
                findUri(ctx, name)?.let { ctx.contentResolver.delete(it, null, null) }
            } catch (_: Exception) { }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, REL_PATH)
            }
            val uri = ctx.contentResolver.insert(downloads(), values)
            if (uri == null) {
                lastError = "insert returned null for $name"
                return false
            }
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: run {
                    lastError = "openOutputStream null for $name"
                    return false
                }
            true
        } catch (e: Exception) {
            lastError = "write $name: ${e.javaClass.simpleName} ${e.message}"
            false
        }
    }

    /** Move legacy private-dir samples (v8) to public folder. Returns moved count. */
    fun migrateLegacy(ctx: Context): Int {
        var n = 0
        try {
            val legacy = File(ctx.getExternalFilesDir(null), "samples")
            if (!legacy.isDirectory) return 0
            for (f in legacy.listFiles() ?: return 0) {
                val mime = when {
                    f.name.endsWith(".json") -> "application/json"
                    else -> "image/jpeg"
                }
                if (writePublic(ctx, f.name, mime, f.readBytes())) {
                    f.delete()
                    if (f.name.endsWith(".json")) n++
                }
            }
        } catch (_: Exception) { }
        return n
    }

    /**
     * @param bitmap decoded (possibly sampled) bitmap shown in the analyzer
     * @param imgScale full_image_px / bitmap_px
     * @param box board box in BITMAP pixels
     * @param markers S + P1..P4 in BITMAP pixels
     * @param markRadii tapped radii in BITMAP pixels
     * @param coins tapped coins (true radii, BITMAP pixels)
     * @param best accepted prediction (optional auxiliary label)
     */
    fun export(
        ctx: Context,
        fullUri: Uri,
        bitmap: Bitmap,
        imgScale: Float,
        box: RectF,
        markers: Map<String, PointF>,
        markRadii: Map<String, Float>,
        coins: List<InCoin>,
        playWhite: Boolean,
        best: Predictor.Shot?,
    ): String? {
        fun fail(stage: String, e: Exception? = null): String? {
            lastError = if (e != null) "$stage: ${e.javaClass.simpleName} ${e.message}" else stage
            return null
        }
        try {
            if (box.width() < 1f || box.height() < 1f) return fail("bad box ${box.width()}x${box.height()}")
            val id = "shot_%04d".format(count(ctx) + 1)

            val fullBytes: ByteArray = try {
                ctx.contentResolver.openInputStream(fullUri)?.use { it.readBytes() }
            } catch (e: Exception) {
                return fail("read picked image", e)
            } ?: return fail("picked image unreadable (permission lost?)")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(fullBytes, 0, fullBytes.size, bounds)
            val sha1 = MessageDigest.getInstance("SHA-1").digest(fullBytes)
                .joinToString("") { "%02x".format(it) }

            val fbox = RectF(
                box.left * imgScale, box.top * imgScale,
                box.right * imgScale, box.bottom * imgScale,
            )
            val bx = box.left.toInt().coerceIn(0, (bitmap.width - 1).coerceAtLeast(0))
            val by = box.top.toInt().coerceIn(0, (bitmap.height - 1).coerceAtLeast(0))
            val bw = box.width().toInt().coerceIn(1, (bitmap.width - bx).coerceAtLeast(1))
            val bh = box.height().toInt().coerceIn(1, (bitmap.height - by).coerceAtLeast(1))
            val crop = try {
                Bitmap.createScaledBitmap(
                    Bitmap.createBitmap(bitmap, bx, by, bw, bh),
                    CROP_OUT, CROP_OUT, true,
                )
            } catch (e: Exception) {
                return fail("crop bitmap ${bw}x$bh from ${bitmap.width}x${bitmap.height}", e)
            }
            val cropBytes = java.io.ByteArrayOutputStream().let {
                crop.compress(Bitmap.CompressFormat.JPEG, 92, it)
                it.toByteArray()
            }
            crop.recycle()

            // frames: bitmap-px -> full / crop_n / board600 (uniform sample assumed)
            fun to600(p: PointF) =
                ((p.x - box.left) / box.width() * 600f) to
                    ((p.y - box.top) / box.height() * 600f)
            fun fullOf(p: PointF) = (p.x * imgScale) to (p.y * imgScale)
            fun cropN(p: PointF) =
                ((p.x - box.left) / box.width()) to ((p.y - box.top) / box.height())
            fun rOut(rImg: Float) = rImg / box.width() * CROP_OUT

            val counters = mutableMapOf("black" to 0, "white" to 0, "red" to 0)
            val coinsArr = JSONArray()
            val occByIdx = coins.indices.map { i ->
                var occ = 0
                for ((j, o) in coins.withIndex()) {
                    if (i == j) continue
                    val dd = hypot(
                        (o.p.x - coins[i].p.x).toDouble(),
                        (o.p.y - coins[i].p.y).toDouble(),
                    ).toFloat()
                    val r = coins[i].rImg
                    if (dd < 2 * r) {
                        val pct = circleOverlapPct(r, o.rImg, dd)
                        if (pct > occ) occ = pct.toInt()
                    }
                }
                occ
            }
            for ((i, c) in coins.withIndex()) {
                val n = (counters[c.type] ?: 0) + 1
                counters[c.type] = n
                val cid = when (c.type) {
                    "black" -> "B$n"
                    "white" -> "W$n"
                    else -> "Q"
                }
                val b600 = to600(c.p)
                val f = fullOf(c.p)
                val cn = cropN(c.p)
                coinsArr.put(JSONObject()
                    .put("id", cid)
                    .put("type", c.type)
                    .put("full_px", arr(f.first, f.second))
                    .put("crop_n", arr(cn.first, cn.second))
                    .put("board600", arr(b600.first, b600.second))
                    .put("r_px", rOut(c.rImg))
                    .put("occluded_pct", occByIdx[i])
                    .put("cluster", occByIdx[i] > 0))
            }

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
                    .put("r_px", rOut(markRadii[key] ?: (box.width() * 25f / 600f)))
                    .put("mine", false))
            }

            val sObj = markers["S"]?.let { m ->
                val b600 = to600(m)
                val f = fullOf(m)
                val cn = cropN(m)
                JSONObject()
                    .put("owner", "me")
                    .put("full_px", arr(f.first, f.second))
                    .put("crop_n", arr(cn.first, cn.second))
                    .put("board600", arr(b600.first, b600.second))
                    .put("r_px", rOut(markRadii["S"] ?: (box.width() * 20f / 600f)))
                    .put("state", "placed")
            }

            val nBlack = counters["black"] ?: 0
            val nWhite = counters["white"] ?: 0
            val nRed = counters["red"] ?: 0

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
                .put("overlapping_cluster", occByIdx.any { it > 0 }))
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
                    .put("score", best.score)
                    .put("kind", best.kind))
            }
            if (!writePublic(ctx, "$id.json", "application/json", root.toString(1).toByteArray())) {
                return fail("MediaStore insert JSON: $lastError")
            }
            if (!writePublic(ctx, "$id.jpg", "image/jpeg", cropBytes)) {
                return fail("MediaStore insert crop: $lastError")
            }
            if (!writePublic(ctx, "$id-full.jpg", "image/jpeg", fullBytes)) {
                return fail("MediaStore insert full: $lastError")
            }
            lastError = ""
            return id
        } catch (e: Exception) {
            return fail("unexpected", e)
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

    @Suppress("unused")
    private fun downloadsPathHint(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/CarromSamples/"
}
