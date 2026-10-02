package com.example.minapp

import kotlin.math.*
import java.util.ArrayList

/**
 * ULTRA search: seeded candidates + global angular sweep, every candidate
 * FULLY SIMULATED (Sim.kt). No manual aim anywhere — the sweep covers the
 * whole board pixel-by-pixel until the best pots are found.
 *
 * Kinds: DIRECT, BANK (cushion first), COMBO (via another ball).
 * Sort: kind rank first (directs above banks above combos), then score.
 */
object Search {

    data class Found(
        val angleDeg: Float,
        val angleRad: Float,
        val speed: Float,
        val targetIdx: Int,
        val pocketIdx: Int,
        val viaIdx: Int, // combo middle ball, -1 otherwise
        val kind: String,
        val score: Float,
        val reason: String,
        val outcome: Sim.Outcome,
    )

    var lastStats = ""
        private set
    /** Per-coin verdict lines for the Why dialog. */
    var lastNotes: List<String> = emptyList()
        private set

    private data class Seed(
        val angle: Float,
        val targetIdx: Int,
        val pocketIdx: Int,
        val viaIdx: Int,
        val hint: String,
        val winDeg: Float,
        val stepDeg: Float,
        val speeds: List<Float>,
    )

    fun fast(
        striker: Pair<Float, Float>,
        strikerR: Float,
        coins: List<Predictor.Coin>,
        playWhite: Boolean,
        pockets: List<Pair<Float, Float>>,
        pocketR: List<Float>?,
        budgetMs: Long = 1200,
    ): List<Found> {
        val t0 = System.currentTimeMillis()
        val mine = if (playWhite) "white" else "black"
        fun prAt(i: Int) = pocketR?.getOrNull(i) ?: Predictor.POCKET_RADIUS
        val rejects = ArrayList<String>()
        var sims = 0

        // ---------- broad: seeded candidates ----------
        val seeds = ArrayList<Seed>()
        val ownIdx = coins.indices.filter { coins[it].type == mine || coins[it].type == "red" }
        // DIRECT ghosts
        for (i in ownIdx) {
            val c = coins[i]
            for ((pi, p) in pockets.withIndex()) {
                val dx = p.first - c.x
                val dy = p.second - c.y
                val dl = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dl < 1e-6f) continue
                val gx = c.x - dx / dl * (c.r + strikerR)
                val gy = c.y - dy / dl * (c.r + strikerR)
                val ang = atan2((gy - striker.second).toDouble(), (gx - striker.first).toDouble()).toFloat()
                seeds.add(Seed(ang, i, pi, -1, "DIRECT", 1.5f, 0.3f, listOf(Sim.SPEED_MED, Sim.SPEED_HARD)))
            }
        }
        // BANK: cushion-first. Restitution-aware solve: find the wall point W
        // minimizing the angle between the (inelastic) outgoing direction and
        // the ghost — a specular mirror misses by ~2r plus bounce flattening.
        fun solveBankWall(
            fx: Float, fy: Float,
            tx: Float, ty: Float,
            fixed: Float, vertical: Boolean,
        ): Pair<Float, Float>? {
            val nx: Float
            val ny: Float
            if (vertical) {
                nx = if (fixed < 300f) 1f else -1f; ny = 0f
            } else {
                nx = 0f; ny = if (fixed < 300f) 1f else -1f
            }
            fun err(w: Float): Float {
                val wx = if (vertical) fixed else w
                val wy = if (vertical) w else fixed
                var dx = wx - fx
                var dy = wy - fy
                val l = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-6f)
                dx /= l; dy /= l
                val dn = dx * nx + dy * ny
                val ox = dx - dn * (1 + Sim.REST) * nx
                val oy = dy - dn * (1 + Sim.REST) * ny
                var gx = tx - wx
                var gy = ty - wy
                val gl = hypot(gx.toDouble(), gy.toDouble()).toFloat().coerceAtLeast(1e-6f)
                gx /= gl; gy /= gl
                val ol = hypot(ox.toDouble(), oy.toDouble()).toFloat().coerceAtLeast(1e-6f)
                return acos(((ox * gx + oy * gy) / ol).coerceIn(-1f, 1f))
            }
            var a = Sim.LO
            var b = Sim.HI
            val gr = 0.618034f
            var c = b - gr * (b - a)
            var d = a + gr * (b - a)
            repeat(24) {
                if (err(c) < err(d)) {
                    b = d
                } else {
                    a = c
                }
                c = b - gr * (b - a)
                d = a + gr * (b - a)
            }
            val w = (a + b) / 2f
            if (err(w) > 0.17f) return null // > ~10deg: no clean bank here
            return (if (vertical) fixed else w) to (if (vertical) w else fixed)
        }
        for (i in ownIdx) {
            val c = coins[i]
            for ((pi, p) in pockets.withIndex()) {
                val dx = p.first - c.x
                val dy = p.second - c.y
                val dl = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (dl < 1e-6f) continue
                val gx = c.x - dx / dl * (c.r + strikerR)
                val gy = c.y - dy / dl * (c.r + strikerR)
                val walls = listOf(
                    (Sim.LO + strikerR) to true,
                    (Sim.HI - strikerR) to true,
                    (Sim.LO + strikerR) to false,
                    (Sim.HI - strikerR) to false,
                )
                for ((edge, vertical) in walls) {
                    // solve against the ghost: striker must ARRIVE at G
                    val wpt = solveBankWall(striker.first, striker.second, gx, gy, edge, vertical)
                        ?: continue
                    val ang = atan2(
                        (wpt.second - striker.second).toDouble(),
                        (wpt.first - striker.first).toDouble(),
                    ).toFloat()
                    seeds.add(Seed(ang, i, pi, -1, "BANK", 2.5f, 0.4f, listOf(Sim.SPEED_MED, Sim.SPEED_HARD)))
                }
            }
        }
        // CBANK (coin bank): restitution-aware wall solve sending the COIN
        // to the pocket, then aim the striker at the resulting ghost.
        for (i in ownIdx) {
            val c = coins[i]
            for ((pi, p) in pockets.withIndex()) {
                val walls = listOf(
                    (Sim.LO + c.r) to true,
                    (Sim.HI - c.r) to true,
                    (Sim.LO + c.r) to false,
                    (Sim.HI - c.r) to false,
                )
                for ((edge, vertical) in walls) {
                    val wpt = solveBankWall(c.x, c.y, p.first, p.second, edge, vertical)
                        ?: continue
                    val ux = wpt.first - c.x
                    val uy = wpt.second - c.y
                    val ul = hypot(ux.toDouble(), uy.toDouble()).toFloat()
                    if (ul < 1e-6f) continue
                    val gx = c.x - ux / ul * (c.r + strikerR)
                    val gy = c.y - uy / ul * (c.r + strikerR)
                    val ang = atan2((gy - striker.second).toDouble(), (gx - striker.first).toDouble()).toFloat()
                    seeds.add(Seed(ang, i, pi, -1, "CBANK", 2.5f, 0.4f, listOf(Sim.SPEED_MED, Sim.SPEED_HARD)))
                }
            }
        }
        // COMBO: A (own/queen) -> B (own/queen) -> pocket
        for (a in ownIdx) {
            for (b in ownIdx) {
                if (a == b) continue
                val A = coins[a]
                val B = coins[b]
                if (hypot((B.x - A.x).toDouble(), (B.y - A.y).toDouble()) > 300f) continue
                for ((pi, p) in pockets.withIndex()) {
                    val dx = p.first - B.x
                    val dy = p.second - B.y
                    val dl = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (dl < 1e-6f) continue
                    // A must travel toward ghost point behind B
                    val gxB = B.x - dx / dl * (A.r + B.r)
                    val gyB = B.y - dy / dl * (A.r + B.r)
                    val ax = gxB - A.x
                    val ay = gyB - A.y
                    val al = hypot(ax.toDouble(), ay.toDouble()).toFloat()
                    if (al < 1e-6f) continue
                    // striker aims at A's ghost along same line
                    val hx = A.x - ax / al * (A.r + strikerR)
                    val hy = A.y - ay / al * (A.r + strikerR)
                    val cut = cutBetween(
                        hx - striker.first, hy - striker.second,
                        ax, ay,
                    )
                    if (cut > 60f) continue
                    val ang = atan2((hy - striker.second).toDouble(), (hx - striker.first).toDouble()).toFloat()
                    seeds.add(Seed(ang, b, pi, a, "COMBO", 2.0f, 0.4f, listOf(Sim.SPEED_MED)))
                }
            }
        }

        // ---------- run seeded fine sweep (per-seed windows; banks sweep
        // wide because cushion restitution shifts the true angle off-mirror)
        val found = ArrayList<Found>()
        var seededSims = 0
        for (sd in seeds) {
            if (System.currentTimeMillis() - t0 > budgetMs * 2 / 3) break
            var a = sd.angle - Math.toRadians(sd.winDeg.toDouble()).toFloat()
            val step = Math.toRadians(sd.stepDeg.toDouble()).toFloat()
            val n = (2 * sd.winDeg / sd.stepDeg).toInt() + 1
            repeat(n) {
                for (sp in sd.speeds) {
                    if (System.currentTimeMillis() - t0 > budgetMs * 2 / 3) return@repeat
                    sims++
                    seededSims++
                    eval(
                        striker, strikerR, coins, pockets, pocketR, mine,
                        a, sp, sd.targetIdx, sd.pocketIdx, sd.viaIdx, sd.hint,
                    )?.let { found.add(it) }
                }
                a += step
            }
        }

        // ---------- global fallback sweep (pixel-by-pixel safety net) ----------
        val needGlobal = found.size < 2
        val gStep = if (needGlobal) Math.toRadians(1.0).toFloat() else Math.toRadians(2.0).toFloat()
        var ga = 0f
        while (ga < Math.PI.toFloat() * 2f) {
            if (System.currentTimeMillis() - t0 > budgetMs) break
            sims++
            // global tasks carry no target: accept any own-color pot
            evalGlobal(striker, strikerR, coins, pockets, pocketR, mine, ga, Sim.SPEED_MED)
                ?.let { f ->
                    if (found.none { it.targetIdx == f.targetIdx && it.pocketIdx == f.pocketIdx }) {
                        found.add(f)
                    }
                }
            ga += gStep
        }

        // ---------- rank: kind first, then score ----------
        fun kindRank(k: String) = when (k) {
            "DIRECT" -> 0
            "BANK" -> 1
            "CBANK" -> 1
            else -> 2
        }
        lastStats = "$sims sims (${seededSims} seeded), ${found.size} clean"
        val ranked = found.sortedWith(
            compareBy({ kindRank(it.kind) }, { -it.score }),
        ).take(8)
        val notes = ArrayList<String>()
        notes.add(lastStats)
        for (i in ownIdx) {
            val c = coins[i]
            val best = ranked.firstOrNull { it.targetIdx == i }
            val label = "${c.type}@(${c.x.toInt()},${c.y.toInt()})"
            notes.add(
                if (best == null) "$label: no clean pot"
                else "$label: best ${best.kind} ★${best.score.toInt()}",
            )
        }
        lastNotes = notes
        return ranked
    }

    /**
     * Baseline sweep like the script tool: slide the striker along its
     * baseline zone, run a lite search per spot, keep the best position.
     * Skips spots overlapping coins (same rule as the script).
     * Returns (bestX, bestFound or null if nothing anywhere).
     */
    fun sweepBaseline(
        strikerY: Float,
        strikerR: Float,
        coins: List<Predictor.Coin>,
        playWhite: Boolean,
        pockets: List<Pair<Float, Float>>,
        pocketR: List<Float>?,
        fromX: Float = 80f,
        toX: Float = 520f,
        stepX: Float = 40f,
        budgetEachMs: Long = 350,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Float, Found?> {
        var x = fromX
        var count = 0
        val total = ((toX - fromX) / stepX).toInt() + 1
        var bestX = fromX
        var best: Found? = null
        while (x <= toX + 1e-6f) {
            count++
            onProgress(count, total)
            val blocked = coins.any {
                hypot((x - it.x).toDouble(), (strikerY - it.y).toDouble()) < strikerR + it.r + 1f
            }
            if (!blocked) {
                val top = fast(x to strikerY, strikerR, coins, playWhite, pockets, pocketR, budgetEachMs)
                    .firstOrNull()
                if (top != null && (best == null || top.score > best.score)) {
                    best = top
                    bestX = x
                }
            }
            x += stepX
        }
        return bestX to best
    }

    private fun cutBetween(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val al = hypot(ax.toDouble(), ay.toDouble()).toFloat().coerceAtLeast(1e-6f)
        val bl = hypot(bx.toDouble(), by.toDouble()).toFloat().coerceAtLeast(1e-6f)
        val c = ((ax / al) * (bx / bl) + (ay / al) * (by / bl)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }


    /** Evaluate one seeded task; angle auto-snapped to candidate ghost. */
    private fun eval(
        striker: Pair<Float, Float>,
        strikerR: Float,
        coins: List<Predictor.Coin>,
        pockets: List<Pair<Float, Float>>,
        pocketR: List<Float>?,
        mine: String,
        angle: Float,
        speed: Float,
        targetIdx: Int,
        pocketIdx: Int,
        viaIdx: Int,
        hint: String,
    ): Found? {
        val o = Sim.simulate(striker, strikerR, angle, speed, coins, pockets, pocketR ?: emptyList())
        return finish(striker, coins, mine, targetIdx, pocketIdx, angle, speed, o, hint)
    }

    /** Global task: any own-color pot counts; target = first own potted. */
    private fun evalGlobal(
        striker: Pair<Float, Float>,
        strikerR: Float,
        coins: List<Predictor.Coin>,
        pockets: List<Pair<Float, Float>>,
        pocketR: List<Float>?,
        mine: String,
        angle: Float,
        speed: Float,
    ): Found? {
        val o = Sim.simulate(striker, strikerR, angle, speed, coins, pockets, pocketR ?: emptyList())
        val tgt = o.potted.firstOrNull { coins[it].type == mine || coins[it].type == "red" }
            ?: return null
        val pk = nearestPocket(o, tgt, pockets)
        return finish(striker, coins, mine, tgt, pk, angle, speed, o, "GLOBAL")
    }

    private fun nearestPocket(
        o: Sim.Outcome,
        targetIdx: Int,
        pockets: List<Pair<Float, Float>>,
    ): Int {
        // pocket whose mouth the target trace ends nearest
        val t = o.traces[targetIdx]?.lastOrNull()
        val ref = t ?: o.endStriker
        var bi = 0
        var bd = Float.MAX_VALUE
        for ((i, p) in pockets.withIndex()) {
            val d = hypot(
                (ref.first - p.first).toDouble(),
                (ref.second - p.second).toDouble(),
            ).toFloat()
            if (d < bd) {
                bd = d; bi = i
            }
        }
        return bi
    }

    private fun finish(
        striker: Pair<Float, Float>,
        coins: List<Predictor.Coin>,
        mine: String,
        targetIdx: Int,
        pocketIdx: Int,
        angle: Float,
        speed: Float,
        o: Sim.Outcome,
        hint: String,
    ): Found? {
        if (targetIdx !in o.potted) return null
        val target = coins[targetIdx]
        var score = if (target.type == "red") 70f else 100f
        for (pi in o.potted) {
            if (pi == targetIdx) continue
            val t = coins[pi].type
            when {
                t == "red" -> score -= 40f
                t != mine -> score -= 60f
                else -> score += 10f
            }
        }
        if (o.scratch) score -= 80f
        val fb = o.firstBall()
        val via = if (fb != null && fb != targetIdx) fb else -1
        val kind = when {
            via >= 0 -> "COMBO"
            o.cushionFirst -> "BANK"
            hint == "CBANK" -> "CBANK"
            else -> "DIRECT"
        }
        score *= when (kind) {
            "BANK" -> 0.85f
            "CBANK" -> 0.8f
            "COMBO" -> 0.7f
            else -> 1f
        }
        score *= 0.97f.pow(o.cushionCount.coerceAtMost(6))
        var bestLeave = Float.MAX_VALUE
        for ((i, c) in coins.withIndex()) {
            if (i in o.potted) continue
            if (c.type != mine) continue
            val d = hypot(
                (o.endStriker.first - c.x).toDouble(),
                (o.endStriker.second - c.y).toDouble(),
            ).toFloat()
            if (d < bestLeave) bestLeave = d
        }
        if (bestLeave < Float.MAX_VALUE) score += 10f / (1f + bestLeave / 150f)
        if (speed <= Sim.SPEED_MED) score *= 1.05f
        var deg = (Math.toDegrees(angle.toDouble()).toFloat() + 90f) % 360f
        if (deg < 0) deg += 360f
        val viaTxt = if (via >= 0) "via " else ""
        return Found(
            deg, angle, speed, targetIdx, pocketIdx, via, kind, score,
            "${target.type} $viaTxt$kind", o,
        )
    }
}
