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
        val speed: Float,
        val targetIdx: Int,
        val pocketIdx: Int,
        val viaIdx: Int,
        val hint: String,
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
                seeds.add(Seed(ang, Sim.SPEED_MED, i, pi, -1, "DIRECT"))
            }
        }
        // BANK: cushion-first. Correct mirror construction: reflect the GHOST
        // point (not the pocket) across the cushion, aim the striker straight
        // at it; the sim then validates cushion-first contact. The crossing
        // point must lie on the cushion segment or the seed is dropped.
        fun crossOnWall(
            gx: Float, gy: Float,
            wall: Char,
        ): Pair<Float, Float>? {
            // returns mirrored ghost if S->G' crosses `wall` within bounds
            val (mx, my) = when (wall) {
                'L' -> (2 * Sim.LO - gx) to gy
                'R' -> (2 * Sim.HI - gx) to gy
                'T' -> gx to (2 * Sim.LO - gy)
                else -> gx to (2 * Sim.HI - gy)
            }
            val dx = mx - striker.first
            val dy = my - striker.second
            if (wall == 'L' || wall == 'R') {
                val edge = if (wall == 'L') Sim.LO else Sim.HI
                if (abs(dx) < 1e-6f) return null
                val t = (edge - striker.first) / dx
                if (t <= 0f || t >= 1f) return null
                val y = striker.second + t * dy
                if (y < Sim.LO || y > Sim.HI) return null
            } else {
                val edge = if (wall == 'T') Sim.LO else Sim.HI
                if (abs(dy) < 1e-6f) return null
                val t = (edge - striker.second) / dy
                if (t <= 0f || t >= 1f) return null
                val x = striker.first + t * dx
                if (x < Sim.LO || x > Sim.HI) return null
            }
            return mx to my
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
                for (w in listOf('L', 'R', 'T', 'B')) {
                    val m = crossOnWall(gx, gy, w) ?: continue
                    val ang = atan2(
                        (m.second - striker.second).toDouble(),
                        (m.first - striker.first).toDouble(),
                    ).toFloat()
                    seeds.add(Seed(ang, Sim.SPEED_MED, i, pi, -1, "BANK"))
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
                    seeds.add(Seed(ang, Sim.SPEED_MED, b, pi, a, "COMBO"))
                }
            }
        }

        // ---------- run seeded fine sweep ----------
        val found = ArrayList<Found>()
        var seededSims = 0
        for (sd in seeds) {
            if (System.currentTimeMillis() - t0 > budgetMs * 2 / 3) break
            var a = sd.angle - Math.toRadians(1.5).toFloat()
            val step = Math.toRadians(0.3).toFloat()
            repeat(11) {
                for (sp in listOf(Sim.SPEED_MED, Sim.SPEED_HARD)) {
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
            else -> "DIRECT"
        }
        score *= when (kind) {
            "BANK" -> 0.85f
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
