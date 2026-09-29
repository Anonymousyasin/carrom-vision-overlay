package com.example.minapp

import kotlin.math.*

// Port of prototype/physics.py — no AI API, pure geometry.
// Board space 600x600, TL=(0,0). Same constants as Carrom-Vision repo.
object Predictor {
    const val BOARD_SIZE = 600f
    const val BOARD_PADDING = 50f
    const val POCKET_RADIUS = 25f
    const val COIN_RADIUS = 15f
    const val STRIKER_RADIUS = 20f

    val POCKETS = listOf(
        Pair(BOARD_PADDING, BOARD_PADDING),
        Pair(BOARD_SIZE - BOARD_PADDING, BOARD_PADDING),
        Pair(BOARD_PADDING, BOARD_SIZE - BOARD_PADDING),
        Pair(BOARD_SIZE - BOARD_PADDING, BOARD_SIZE - BOARD_PADDING),
    )

    data class Coin(val x: Float, val y: Float, val type: String)
    data class Shot(
        val targetX: Float, val targetY: Float,
        val ghostX: Float, val ghostY: Float,
        val angleDeg: Float, val angleRad: Float,
        val pocketX: Float, val pocketY: Float,
        val score: Float, val reason: String,
    )
    data class Prediction(
        val strikerPath: List<Pair<Float, Float>>,
        val coinHit: Coin?,
        val coinPath: List<Pair<Float, Float>>,
        val pocket: Pair<Float, Float>?,
        val best: Shot?,
    )

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) =
        hypot((a.first - b.first).toDouble(), (a.second - b.second).toDouble()).toFloat()

    private fun rayToWall(pos: Pair<Float, Float>, angle: Float): Pair<Pair<Float, Float>?, String?> {
        val dx = cos(angle.toDouble()).toFloat()
        val dy = sin(angle.toDouble()).toFloat()
        var tBest = Float.MAX_VALUE
        var wall: String? = null
        val lo = BOARD_PADDING
        val hi = BOARD_SIZE - BOARD_PADDING
        if (abs(dx) > 1e-6f) {
            for ((edge, w) in listOf(lo to "L", hi to "R")) {
                val t = (edge - pos.first) / dx
                if (t > 0 && t < tBest) {
                    val y = pos.second + t * dy
                    if (y in lo - 1e-3f..hi + 1e-3f) { tBest = t; wall = w }
                }
            }
        }
        if (abs(dy) > 1e-6f) {
            for ((edge, w) in listOf(lo to "T", hi to "B")) {
                val t = (edge - pos.second) / dy
                if (t > 0 && t < tBest) {
                    val x = pos.first + t * dx
                    if (x in lo - 1e-3f..hi + 1e-3f) { tBest = t; wall = w }
                }
            }
        }
        if (wall == null) return null to null
        return Pair(pos.first + tBest * dx, pos.second + tBest * dy) to wall
    }

    private fun reflect(angle: Float, wall: String?): Float = when (wall) {
        "T", "B" -> -angle
        "L", "R" -> (Math.PI.toFloat() - angle)
        else -> angle
    }

    data class Paths(
        val strikerPath: List<Pair<Float, Float>>,
        val hit: Coin?,
        val coinPath: List<Pair<Float, Float>>,
        /** Striker motion after contact (equal-mass tangent deflection). */
        val strikerAfter: List<Pair<Float, Float>>,
    )

    /**
     * Exact physics v4:
     * - ray vs circle radius R1+R2 solved per coin, nearest positive t wins
     * - striker center travels to the CONTACT point (not coin center)
     * - coin leaves along line-of-centers N; striker deflects to tangent
     *   V2 = D - (D.N)N (equal mass elastic), drawn with 1 cushion bounce
     */
    fun predictPath(
        striker: Pair<Float, Float>, angle: Float,
        coins: List<Coin>, maxRebounds: Int = 1,
    ): Paths {
        val dx = cos(angle.toDouble()).toFloat()
        val dy = sin(angle.toDouble()).toFloat()
        val rHit = COIN_RADIUS + STRIKER_RADIUS

        var best: Coin? = null
        var bestT = Float.MAX_VALUE
        for (c in coins) {
            val ox = c.x - striker.first
            val oy = c.y - striker.second
            val tca = ox * dx + oy * dy
            if (tca < 0) continue
            val d2 = ox * ox + oy * oy - tca * tca
            if (d2 > rHit * rHit) continue
            val thc = sqrt((rHit * rHit - d2).toDouble()).toFloat()
            val t = tca - thc
            if (t > 1e-3f && t < bestT) {
                best = c; bestT = t
            }
        }

        // no contact: striker runs walls like before
        if (best == null) {
            val sPath = mutableListOf(striker)
            var pos = striker
            var ang = angle
            repeat(maxRebounds + 1) {
                val (hit, wall) = rayToWall(pos, ang)
                if (hit == null) return@repeat
                sPath.add(hit)
                var pocketed = false
                for (p in POCKETS) if (dist(hit, p) < POCKET_RADIUS * 1.5f) { pocketed = true; break }
                if (pocketed) return@repeat
                pos = hit; ang = reflect(ang, wall)
            }
            return Paths(sPath, null, emptyList(), emptyList())
        }

        val contact = striker.first + dx * bestT to striker.second + dy * bestT
        // line of centers at contact
        var nx = best.x - contact.first
        var ny = best.y - contact.second
        val nl = hypot(nx.toDouble(), ny.toDouble()).toFloat().coerceAtLeast(1e-6f)
        nx /= nl; ny /= nl

        // coin path from coin center along N
        val cAngle = atan2(ny.toDouble(), nx.toDouble()).toFloat()
        val cPath = mutableListOf(best.x to best.y)
        var cp = best.x to best.y
        var ca = cAngle
        repeat(maxRebounds + 1) {
            val (hit, wall) = rayToWall(cp, ca)
            if (hit == null) return@repeat
            cPath.add(hit)
            var pocketed = false
            for (p in POCKETS) if (dist(hit, p) < POCKET_RADIUS * 1.5f) { pocketed = true; break }
            if (pocketed) return@repeat
            cp = hit; ca = reflect(ca, wall)
        }

        // striker deflection: tangent component survives
        val dot = dx * nx + dy * ny
        var vx = dx - dot * nx
        var vy = dy - dot * ny
        val vl = hypot(vx.toDouble(), vy.toDouble()).toFloat()
        val sAfter = mutableListOf(contact)
        if (vl > 0.15f) {
            vx /= vl; vy /= vl
            // roll-out ~7 coin radii, with one cushion bounce
            val rollLen = COIN_RADIUS * 7f
            val endX = contact.first + vx * rollLen
            val endY = contact.second + vy * rollLen
            val lo = BOARD_PADDING
            val hi = BOARD_SIZE - BOARD_PADDING
            var tWall = Float.MAX_VALUE
            var wall: String? = null
            if (vx > 1e-6f) { val t = (hi - contact.first) / vx; if (t > 0 && t < tWall) { tWall = t; wall = "R" } }
            if (vx < -1e-6f) { val t = (lo - contact.first) / vx; if (t > 0 && t < tWall) { tWall = t; wall = "L" } }
            if (vy > 1e-6f) { val t = (hi - contact.second) / vy; if (t > 0 && t < tWall) { tWall = t; wall = "B" } }
            if (vy < -1e-6f) { val t = (lo - contact.second) / vy; if (t > 0 && t < tWall) { tWall = t; wall = "T" } }
            if (wall != null && tWall < rollLen) {
                val hitW = contact.first + vx * tWall to contact.second + vy * tWall
                sAfter.add(hitW)
                val vAng = reflect(atan2(vy.toDouble(), vx.toDouble()).toFloat(), wall)
                val rest = rollLen - tWall
                sAfter.add(
                    hitW.first + cos(vAng.toDouble()).toFloat() * rest to
                        hitW.second + sin(vAng.toDouble()).toFloat() * rest,
                )
            } else {
                sAfter.add(endX to endY)
            }
        }
        return Paths(listOf(striker, contact), best, cPath, sAfter)
    }

    private fun segClear(a: Pair<Float, Float>, b: Pair<Float, Float>, coins: List<Coin>, ignore: Coin?): Boolean {
        val dx = b.first - a.first
        val dy = b.second - a.second
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1e-6f) return true
        val nx = dx / len
        val ny = dy / len
        for (c in coins) {
            if (c == ignore) continue
            val rx = c.x - a.first
            val ry = c.y - a.second
            val t = rx * nx + ry * ny
            if (t > 0 && t < len && abs(rx * ny - ry * nx) < COIN_RADIUS * 2) return false
        }
        return true
    }

    fun bestShot(
        striker: Pair<Float, Float>,
        coins: List<Coin>,
        playWhite: Boolean = true,
    ): Shot? {
        val mine = if (playWhite) "white" else "black"
        var best: Shot? = null
        for (c in coins) {
            if (c.type != mine && c.type != "red") continue
            for (p in POCKETS) {
                val ddx = p.first - c.x
                val ddy = p.second - c.y
                val dlen = hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
                if (dlen < 1e-6f) continue
                val gx = c.x - ddx / dlen * 2 * COIN_RADIUS
                val gy = c.y - ddy / dlen * 2 * COIN_RADIUS
                val sdx = gx - striker.first
                val sdy = gy - striker.second
                val slen = hypot(sdx.toDouble(), sdy.toDouble()).toFloat()
                if (slen < 1e-6f) continue
                if (!segClear(striker, gx to gy, coins, c)) continue
                if (!segClear(c.x to c.y, p, coins, c)) continue
                val cosCut = ((sdx / slen) * (ddx / dlen) + (sdy / slen) * (ddy / dlen)).coerceIn(-1f, 1f)
                val cut = Math.toDegrees(acos(cosCut.toDouble())).toFloat()
                if (cut > 75f) continue
                val d1 = dist(striker, gx to gy)
                val d2 = dist(c.x to c.y, p)
                var score = 1f / (1f + d1 / 300f + d2 / 300f + cut / 45f)
                if (c.type == "red") score *= 0.85f
                val rad = atan2(sdy.toDouble(), sdx.toDouble()).toFloat()
                var deg = (Math.toDegrees(rad.toDouble()).toFloat() + 90f) % 360f
                if (deg < 0) deg += 360f
                if (best == null || score > best.score) {
                    best = Shot(c.x, c.y, gx, gy, deg, rad, p.first, p.second, score,
                        "${c.type} to pocket $p cut=${cut.toInt()}°")
                }
            }
        }
        return best
    }

    fun fullPrediction(
        striker: Pair<Float, Float>,
        coins: List<Coin>,
        playWhite: Boolean = true,
    ): Prediction {
        val best = bestShot(striker, coins, playWhite)
        val angle = best?.angleRad ?: (-Math.PI / 2).toFloat()
        val res = predictPath(striker, angle, coins)
        var pocket: Pair<Float, Float>? = null
        for (p in res.coinPath + res.strikerPath + res.strikerAfter) {
            for (pk in POCKETS) if (dist(p, pk) < POCKET_RADIUS * 1.5f) { pocket = pk; break }
        }
        return Prediction(res.strikerPath, res.hit, res.coinPath, pocket, best)
    }

    /** Manual-table aim: angle that pots [target] into [pocket] from [striker]. */
    fun aimAt(
        striker: Pair<Float, Float>,
        target: Pair<Float, Float>,
        pocket: Pair<Float, Float>,
    ): Triple<Float, Float, Float> {
        val pdx = pocket.first - target.first
        val pdy = pocket.second - target.second
        val plen = hypot(pdx.toDouble(), pdy.toDouble()).toFloat()
        val gx = target.first - pdx / plen * 2 * COIN_RADIUS
        val gy = target.second - pdy / plen * 2 * COIN_RADIUS
        val sdx = gx - striker.first
        val sdy = gy - striker.second
        val slen = hypot(sdx.toDouble(), sdy.toDouble()).toFloat().coerceAtLeast(1e-6f)
        val rad = atan2(sdy.toDouble(), sdx.toDouble()).toFloat()
        var deg = (Math.toDegrees(rad.toDouble()).toFloat() + 90f) % 360f
        if (deg < 0) deg += 360f
        val cosCut = ((sdx / slen) * (pdx / plen) + (sdy / slen) * (pdy / plen)).coerceIn(-1f, 1f)
        val cut = Math.toDegrees(acos(cosCut.toDouble())).toFloat()
        return Triple(rad, deg, cut)
    }

    /** Demo layout estimated from real pool1.jpg center cluster. */
    fun demoState(): Pair<Pair<Float, Float>, List<Coin>> {
        val striker = 460f to 360f
        val coins = listOf(
            Coin(300f, 300f, "red"),
            Coin(280f, 280f, "white"),
            Coin(320f, 275f, "white"),
            Coin(335f, 295f, "black"),
            Coin(290f, 315f, "white"),
            Coin(315f, 320f, "black"),
            Coin(270f, 300f, "black"),
            Coin(350f, 300f, "black"),
            Coin(290f, 350f, "black"),
            Coin(380f, 400f, "white"),
            Coin(420f, 310f, "white"),
        )
        return striker to coins
    }
}
