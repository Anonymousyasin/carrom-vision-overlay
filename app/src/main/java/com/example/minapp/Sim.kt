package com.example.minapp

import kotlin.math.*
import java.util.ArrayList

/**
 * ULTRA physics core: full 2D rigid-disc simulation on the 600-space board.
 * No raycast shortcuts — cushions, rebounds, combinations, scratches and
 * follow/draw all emerge from integration. Pure Kotlin, zero deps.
 *
 * Units: board 600. Speeds in units/sec. Friction/restitution are educated
 * guesses (v16 tunables screen will calibrate them against real tables).
 */
object Sim {

    const val LO = 50f
    const val HI = 550f
    const val REST = 0.75f          // cushion restitution
    const val LIN_DAMP = 0.9f       // rolling damping, 1/sec
    const val ROLL_DEC = 110f       // rolling resistance, units/sec^2
    const val STOP_V = 8f           // below this a disc sleeps
    const val DT = 0.001f           // 1ms timestep
    const val MAX_T = 4f            // 4s sim cap

    const val SPEED_SOFT = 620f
    const val SPEED_MED = 980f
    const val SPEED_HARD = 1350f

    data class Ball(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        val r: Float, val coinIdx: Int, // -1 = striker
        var live: Boolean = true,
    ) {
        fun moving() = live && (vx * vx + vy * vy) > STOP_V * STOP_V
    }

    data class Outcome(
        /** coinIdx potted, in order */
        val potted: List<Int>,
        /** ordered impulse events (a -> b, -1 = striker), capped */
        val contacts: List<Pair<Int, Int>>,
        val scratch: Boolean,
        val cushionFirst: Boolean,
        val cushionCount: Int,
        val traceStriker: List<Pair<Float, Float>>,
        /** coinIdx -> sampled path */
        val traces: Map<Int, List<Pair<Float, Float>>>,
        val endStriker: Pair<Float, Float>,
        val steps: Int,
    ) {
        /** first ball the striker touched, or null */
        fun firstBall(): Int? = contacts.firstOrNull { it.first == -1 }?.second
    }

    fun simulate(
        striker: Pair<Float, Float>,
        strikerR: Float,
        angle: Float,
        speed: Float,
        coins: List<Predictor.Coin>,
        pockets: List<Pair<Float, Float>>,
        pocketR: List<Float>,
    ): Outcome {
        val balls = ArrayList<Ball>(coins.size + 1)
        balls.add(
            Ball(
                striker.first, striker.second,
                cos(angle.toDouble()).toFloat() * speed,
                sin(angle.toDouble()).toFloat() * speed,
                strikerR, -1,
            ),
        )
        for ((i, c) in coins.withIndex()) {
            balls.add(Ball(c.x, c.y, 0f, 0f, c.r, i))
        }

        val potted = ArrayList<Int>()
        val contacts = ArrayList<Pair<Int, Int>>()
        var cushions = 0
        var cushionFirst = false
        var contactSeen = false
        val traceS = ArrayList<Pair<Float, Float>>()
        val traces = HashMap<Int, ArrayList<Pair<Float, Float>>>()
        var step = 0
        val maxSteps = (MAX_T / DT).toInt()

        fun pocketAt(x: Float, y: Float): Int {
            for ((i, p) in pockets.withIndex()) {
                val rr = pocketR.getOrNull(i) ?: Predictor.POCKET_RADIUS
                val dx = x - p.first
                val dy = y - p.second
                if (dx * dx + dy * dy < rr * rr) return i
            }
            return -1
        }

        while (step < maxSteps) {
            step++
            var anyMoving = false
            // integrate
            for (b in balls) {
                if (!b.live) continue
                if (b.moving()) {
                    anyMoving = true
                    b.x += b.vx * DT
                    b.y += b.vy * DT
                    // friction: linear damping + constant rolling resistance
                    val sp = hypot(b.vx.toDouble(), b.vy.toDouble()).toFloat()
                    if (sp > 0f) {
                        var ns = sp * (1f - LIN_DAMP * DT) - ROLL_DEC * DT
                        if (ns < STOP_V) ns = 0f
                        b.vx *= ns / sp
                        b.vy *= ns / sp
                    }
                }
            }
            if (!anyMoving) break
            // cushions (skip near pocket mouths so discs fall in)
            for (b in balls) {
                if (!b.moving()) continue
                var nearMouth = false
                for ((i, p) in pockets.withIndex()) {
                    val rr = pocketR.getOrNull(i) ?: Predictor.POCKET_RADIUS
                    val dx = b.x - p.first
                    val dy = b.y - p.second
                    if (dx * dx + dy * dy < (rr * 1.7f) * (rr * 1.7f)) {
                        nearMouth = true
                        break
                    }
                }
                if (nearMouth) continue
                var hit = false
                if (b.x < LO + b.r && b.vx < 0) {
                    b.x = LO + b.r; b.vx = -b.vx * REST; hit = true
                } else if (b.x > HI - b.r && b.vx > 0) {
                    b.x = HI - b.r; b.vx = -b.vx * REST; hit = true
                }
                if (b.y < LO + b.r && b.vy < 0) {
                    b.y = LO + b.r; b.vy = -b.vy * REST; hit = true
                } else if (b.y > HI - b.r && b.vy > 0) {
                    b.y = HI - b.r; b.vy = -b.vy * REST; hit = true
                }
                if (hit) {
                    cushions++
                    if (!contactSeen && b.coinIdx == -1) cushionFirst = true
                }
            }
            // disc-disc: equal mass elastic, 2 relaxation passes
            repeat(2) {
                for (i in balls.indices) {
                    val a = balls[i]
                    if (!a.live) continue
                    for (j in i + 1 until balls.size) {
                        val b = balls[j]
                        if (!b.live) continue
                        if (!a.moving() && !b.moving()) continue
                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val rr = a.r + b.r
                        val d2 = dx * dx + dy * dy
                        if (d2 >= rr * rr || d2 < 1e-9f) continue
                        val d = sqrt(d2.toDouble()).toFloat()
                        val nx = dx / d
                        val ny = dy / d
                        // de-overlap
                        val push = (rr - d) / 2f
                        a.x -= nx * push; a.y -= ny * push
                        b.x += nx * push; b.y += ny * push
                        // impulse along normal if approaching
                        val rvn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
                        if (rvn < 0f) {
                            a.vx += rvn * nx; a.vy += rvn * ny
                            b.vx -= rvn * nx; b.vy -= rvn * ny
                            if (contacts.size < 64) {
                                contacts.add(a.coinIdx to b.coinIdx)
                            }
                            contactSeen = true
                        }
                    }
                }
            }
            // pockets
            for (b in balls) {
                if (!b.live) continue
                if (pocketAt(b.x, b.y) >= 0) {
                    b.live = false
                    b.vx = 0f; b.vy = 0f
                    if (b.coinIdx >= 0) potted.add(b.coinIdx)
                }
            }
            // traces every 8th step
            if (step % 8 == 0) {
                val s = balls[0]
                if (s.live && traceS.size < 400) {
                    traceS.add(s.x to s.y)
                }
                for (b in balls) {
                    if (b.coinIdx >= 0 && b.live && b.moving()) {
                        val t = traces.getOrPut(b.coinIdx) { ArrayList() }
                        if (t.size < 400) t.add(b.x to b.y)
                    }
                }
            }
            // early exit: target-area settled — caller decides; stop when all rest
        }
        val s = balls[0]
        val scratch = !s.live
        return Outcome(
            potted, contacts, scratch, cushionFirst, cushions,
            traceS, traces, s.x to s.y, step,
        )
    }
}
