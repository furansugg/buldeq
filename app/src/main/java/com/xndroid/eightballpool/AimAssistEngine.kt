package com.xndroid.eightballpool

import android.graphics.PointF
import kotlin.math.*

class AimAssistEngine {

    data class Table(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val ballRadius: Float,
        val pockets: List<Pocket>
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class Pocket(
        val x: Float,
        val y: Float,
        val radius: Float,
        val name: String
    )

    data class RaySegment(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val isBounce: Boolean = false
    )

    data class AimSolution(
        val cueX: Float,
        val cueY: Float,
        val ghostX: Float,
        val ghostY: Float,
        val targetBallX: Float,
        val targetBallY: Float,
        val ballRadius: Float,
        val cueToGhostLine: RaySegment,
        val targetBallPath: List<RaySegment>,
        val cueDeflectionPath: List<RaySegment>,
        val targetPocket: Pocket?
    )

    companion object {
        fun computeTable(screenWidth: Int, screenHeight: Int): Table {
            // 8 Ball Pool is always landscape
            val w = maxOf(screenWidth, screenHeight).toFloat()
            val h = minOf(screenWidth, screenHeight).toFloat()

            // Dimensions calibrated against 8 Ball Pool native layout
            val left = w * 0.178f
            val right = w * 0.822f
            val top = h * 0.198f
            val bottom = h * 0.892f

            val tableH = bottom - top
            val ballR = (tableH / 32f).coerceIn(12f, 32f)
            val pocketR = ballR * 1.6f

            val midX = (left + right) / 2f

            val pockets = listOf(
                Pocket(left + ballR * 0.4f, top + ballR * 0.4f, pocketR, "Top-Left"),
                Pocket(midX, top - ballR * 0.1f, pocketR, "Top-Center"),
                Pocket(right - ballR * 0.4f, top + ballR * 0.4f, pocketR, "Top-Right"),
                Pocket(left + ballR * 0.4f, bottom - ballR * 0.4f, pocketR, "Bottom-Left"),
                Pocket(midX, bottom + ballR * 0.1f, pocketR, "Bottom-Center"),
                Pocket(right - ballR * 0.4f, bottom - ballR * 0.4f, pocketR, "Bottom-Right")
            )

            return Table(left, top, right, bottom, ballR, pockets)
        }
    }

    /**
     * Calculates the complete aim prediction given Cue Ball position, Aim Angle, and Target Ball
     */
    fun calculateAim(
        table: Table,
        cueX: Float,
        cueY: Float,
        aimAngleRad: Float,
        balls: List<PointF>,
        maxBounces: Int = 2
    ): AimSolution? {
        val dirX = cos(aimAngleRad)
        val dirY = sin(aimAngleRad)

        // Find the first ball hit along ray (dirX, dirY)
        val twoR = table.ballRadius * 2f
        val twoRSq = twoR * twoR

        var nearestDist = Float.MAX_VALUE
        var hitTargetBall: PointF? = null
        var hitGhostX = 0f
        var hitGhostY = 0f

        for (ball in balls) {
            val toBx = ball.x - cueX
            val toBy = ball.y - cueY

            // Projection of (toBx, toBy) onto dir
            val proj = toBx * dirX + toBy * dirY
            if (proj <= 5f) continue // Ball is behind or too close

            val perpSq = (toBx * toBx + toBy * toBy) - (proj * proj)
            if (perpSq <= twoRSq && perpSq >= 0f) {
                // Distance to impact
                val offset = sqrt((twoRSq - perpSq).toDouble()).toFloat()
                val dist = proj - offset

                if (dist > 5f && dist < nearestDist) {
                    nearestDist = dist
                    hitTargetBall = ball
                    hitGhostX = cueX + dirX * dist
                    hitGhostY = cueY + dirY * dist
                }
            }
        }

        // If no ball hit directly in ray, shoot straight to cushion
        if (hitTargetBall == null) {
            val cushionHit = rayCastCushion(
                table,
                cueX, cueY,
                dirX, dirY,
                table.ballRadius
            )
            val cueLine = RaySegment(cueX, cueY, cushionHit.x, cushionHit.y, false)
            return AimSolution(
                cueX = cueX,
                cueY = cueY,
                ghostX = cushionHit.x,
                ghostY = cushionHit.y,
                targetBallX = 0f,
                targetBallY = 0f,
                ballRadius = table.ballRadius,
                cueToGhostLine = cueLine,
                targetBallPath = emptyList(),
                cueDeflectionPath = emptyList(),
                targetPocket = findNearPocket(table, cushionHit.x, cushionHit.y)
            )
        }

        // 1. Line from Cue to Ghost Ball
        val cueToGhost = RaySegment(cueX, cueY, hitGhostX, hitGhostY, false)

        // 2. Target Ball Path (Collision Normal)
        var normalX = hitTargetBall.x - hitGhostX
        var normalY = hitTargetBall.y - hitGhostY
        val normalLen = sqrt((normalX * normalX + normalY * normalY).toDouble()).toFloat()
        if (normalLen > 0.001f) {
            normalX /= normalLen
            normalY /= normalLen
        } else {
            normalX = dirX
            normalY = dirY
        }

        val targetPath = tracePathWithBounces(
            table,
            hitTargetBall.x,
            hitTargetBall.y,
            normalX,
            normalY,
            maxBounces
        )

        // 3. Cue Ball Deflection Path (90-degree tangent rule)
        // Deflection vector = V - (V . N) * N
        val dot = dirX * normalX + dirY * normalY
        var tanX = dirX - dot * normalX
        var tanY = dirY - dot * normalY
        val tanLen = sqrt((tanX * tanX + tanY * tanY).toDouble()).toFloat()

        val cueDeflectPath = if (tanLen > 0.05f) {
            tanX /= tanLen
            tanY /= tanLen
            tracePathWithBounces(
                table,
                hitGhostX,
                hitGhostY,
                tanX,
                tanY,
                1 // Cue ball deflection 1 bounce
            )
        } else {
            emptyList()
        }

        // Check if target path enters a pocket
        val lastTargetPoint = targetPath.lastOrNull()
        val targetPocket = if (lastTargetPoint != null) {
            findNearPocket(table, lastTargetPoint.endX, lastTargetPoint.endY)
        } else null

        return AimSolution(
            cueX = cueX,
            cueY = cueY,
            ghostX = hitGhostX,
            ghostY = hitGhostY,
            targetBallX = hitTargetBall.x,
            targetBallY = hitTargetBall.y,
            ballRadius = table.ballRadius,
            cueToGhostLine = cueToGhost,
            targetBallPath = targetPath,
            cueDeflectionPath = cueDeflectPath,
            targetPocket = targetPocket
        )
    }

    private fun tracePathWithBounces(
        table: Table,
        startX: Float,
        startY: Float,
        dirX: Float,
        dirY: Float,
        maxBounces: Int
    ): List<RaySegment> {
        val segments = mutableListOf<RaySegment>()
        var currX = startX
        var currY = startY
        var currDx = dirX
        var currDy = dirY

        for (bounce in 0..maxBounces) {
            val hit = rayCastCushionWithNormal(
                table,
                currX, currY,
                currDx, currDy,
                table.ballRadius
            )

            segments.add(RaySegment(currX, currY, hit.x, hit.y, bounce > 0))

            // Check if hit a pocket -> path terminates in pocket!
            val pocket = findNearPocket(table, hit.x, hit.y)
            if (pocket != null) {
                break
            }

            // Bounce off cushion
            currX = hit.x
            currY = hit.y

            if (hit.hitHorizontal) {
                currDy = -currDy
            }
            if (hit.hitVertical) {
                currDx = -currDx
            }

            // Prevent infinite loop on edge cases
            if (abs(currDx) < 0.001f && abs(currDy) < 0.001f) break
        }

        return segments
    }

    private data class CushionHit(
        val x: Float,
        val y: Float,
        val hitHorizontal: Boolean,
        val hitVertical: Boolean
    )

    private fun rayCastCushion(
        table: Table,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        r: Float
    ): PointF {
        val hit = rayCastCushionWithNormal(table, x, y, dx, dy, r)
        return PointF(hit.x, hit.y)
    }

    private fun rayCastCushionWithNormal(
        table: Table,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        r: Float
    ): CushionHit {
        val minX = table.left + r
        val maxX = table.right - r
        val minY = table.top + r
        val maxY = table.bottom - r

        var minT = Float.MAX_VALUE
        var hitH = false
        var hitV = false

        // Vertical cushions (Left / Right)
        if (dx < -0.0001f) {
            val t = (minX - x) / dx
            if (t > 0.001f && t < minT) {
                minT = t
                hitV = true
                hitH = false
            }
        } else if (dx > 0.0001f) {
            val t = (maxX - x) / dx
            if (t > 0.001f && t < minT) {
                minT = t
                hitV = true
                hitH = false
            }
        }

        // Horizontal cushions (Top / Bottom)
        if (dy < -0.0001f) {
            val t = (minY - y) / dy
            if (t > 0.001f && t < minT) {
                minT = t
                hitH = true
                hitV = false
            }
        } else if (dy > 0.0001f) {
            val t = (maxY - y) / dy
            if (t > 0.001f && t < minT) {
                minT = t
                hitH = true
                hitV = false
            }
        }

        if (minT == Float.MAX_VALUE) {
            minT = 200f
        }

        val hitX = (x + dx * minT).coerceIn(minX, maxX)
        val hitY = (y + dy * minT).coerceIn(minY, maxY)

        return CushionHit(hitX, hitY, hitH, hitV)
    }

    private fun findNearPocket(table: Table, x: Float, y: Float): Pocket? {
        val threshold = table.ballRadius * 1.8f
        val thresholdSq = threshold * threshold

        for (pocket in table.pockets) {
            val dx = x - pocket.x
            val dy = y - pocket.y
            if (dx * dx + dy * dy <= thresholdSq) {
                return pocket
            }
        }
        return null
    }
}
