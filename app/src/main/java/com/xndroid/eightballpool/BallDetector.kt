package com.xndroid.eightballpool

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import kotlin.math.*

class BallDetector {

    private val engine = AimAssistEngine()

    data class DetectionResult(
        val solution: AimAssistEngine.AimSolution?,
        val cueBall: PointF?,
        val balls: List<PointF>,
        val table: AimAssistEngine.Table,
        val timestamp: Long
    )

    var width: Int = 1920
    var height: Int = 1080

    // Cache last valid aim angle to avoid jitter
    private var lastValidAngle: Float? = null
    private var lastCuePos: PointF? = null

    fun detect(bitmap: Bitmap, scaleX: Float = 1f, scaleY: Float = 1f): DetectionResult {
        val table = AimAssistEngine.computeTable(width, height)
        val bW = bitmap.width
        val bH = bitmap.height

        // Calculate table bounds in bitmap coordinates
        val bTableLeft = (table.left / scaleX).toInt().coerceIn(0, bW - 1)
        val bTableRight = (table.right / scaleX).toInt().coerceIn(0, bW - 1)
        val bTableTop = (table.top / scaleY).toInt().coerceIn(0, bH - 1)
        val bTableBottom = (table.bottom / scaleY).toInt().coerceIn(0, bH - 1)

        val bRadius = (table.ballRadius / scaleX).coerceAtLeast(6f)

        // 1. Sample felt color at center
        val centerPixel = bitmap.getPixel(bW / 2, bH / 2)
        val feltR = Color.red(centerPixel)
        val feltG = Color.green(centerPixel)
        val feltB = Color.blue(centerPixel)

        // 2. Scan for Cue Ball (brightest white circular blob) and other balls
        val step = 4
        var bestWhiteScore = 0
        var cueX = 0f
        var cueY = 0f
        var cueFound = false

        val balls = mutableListOf<PointF>()
        val ballCandidates = mutableListOf<PointF>()

        for (y in bTableTop until bTableBottom step step) {
            for (x in bTableLeft until bTableRight step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Check difference from felt
                val colorDist = abs(r - feltR) + abs(g - feltG) + abs(b - feltB)
                if (colorDist < 45) continue // Felt pixel

                // Check for White (Cue Ball / Guideline)
                val brightness = (r + g + b) / 3
                val isWhite = r > 215 && g > 215 && b > 215

                if (isWhite && brightness > bestWhiteScore) {
                    // Check if it's a ball (surrounded by white pixels)
                    if (isCircularWhite(bitmap, x, y, (bRadius * 0.5f).toInt())) {
                        bestWhiteScore = brightness
                        cueX = x * scaleX
                        cueY = y * scaleY
                        cueFound = true
                    }
                } else if (colorDist > 70) {
                    // Possible target ball candidate
                    ballCandidates.add(PointF(x * scaleX, y * scaleY))
                }
            }
        }

        // Cluster target balls (minimum distance 2 * radius)
        val minBallDistSq = (table.ballRadius * 1.6f) * (table.ballRadius * 1.6f)
        for (cand in ballCandidates) {
            if (cueFound) {
                val toCueSq = (cand.x - cueX) * (cand.x - cueX) + (cand.y - cueY) * (cand.y - cueY)
                if (toCueSq < minBallDistSq) continue
            }

            var tooClose = false
            for (b in balls) {
                val dSq = (cand.x - b.x) * (cand.x - b.x) + (cand.y - b.y) * (cand.y - b.y)
                if (dSq < minBallDistSq) {
                    tooClose = true
                    break
                }
            }
            if (!tooClose && balls.size < 15) {
                balls.add(cand)
            }
        }

        val cuePoint = if (cueFound) PointF(cueX, cueY) else lastCuePos

        var solution: AimAssistEngine.AimSolution? = null

        if (cuePoint != null) {
            lastCuePos = cuePoint
            val bCueX = (cuePoint.x / scaleX).toInt().coerceIn(0, bW - 1)
            val bCueY = (cuePoint.y / scaleY).toInt().coerceIn(0, bH - 1)

            // 3. Find aim angle by searching white guideline radiating from cue ball
            val aimAngle = findAimAngle(bitmap, bCueX, bCueY, bRadius) ?: lastValidAngle

            if (aimAngle != null) {
                lastValidAngle = aimAngle
                solution = engine.calculateAim(
                    table = table,
                    cueX = cuePoint.x,
                    cueY = cuePoint.y,
                    aimAngleRad = aimAngle,
                    balls = balls,
                    maxBounces = 2
                )
            }
        }

        return DetectionResult(
            solution = solution,
            cueBall = cuePoint,
            balls = balls,
            table = table,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun isCircularWhite(bitmap: Bitmap, cx: Int, cy: Int, r: Int): Boolean {
        var whiteCount = 0
        val offsets = arrayOf(-r, 0, r)
        for (ox in offsets) {
            for (oy in offsets) {
                val nx = (cx + ox).coerceIn(0, bitmap.width - 1)
                val ny = (cy + oy).coerceIn(0, bitmap.height - 1)
                val p = bitmap.getPixel(nx, ny)
                if (Color.red(p) > 200 && Color.green(p) > 200 && Color.blue(p) > 200) {
                    whiteCount++
                }
            }
        }
        return whiteCount >= 6
    }

    /**
     * Finds the direction of the in-game white aiming line around the cue ball
     */
    private fun findAimAngle(bitmap: Bitmap, cueX: Int, cueY: Int, ballRadius: Float): Float? {
        val testRadius1 = (ballRadius * 1.8f).toInt()
        val testRadius2 = (ballRadius * 2.8f).toInt()

        var bestScore = 0
        var bestAngle: Float? = null

        // Sample 72 angles (every 5 degrees)
        for (i in 0 until 72) {
            val angle = (i * 5) * (PI.toFloat() / 180f)
            val cosA = cos(angle)
            val sinA = sin(angle)

            val x1 = (cueX + cosA * testRadius1).toInt()
            val y1 = (cueY + sinA * testRadius1).toInt()
            val x2 = (cueX + cosA * testRadius2).toInt()
            val y2 = (cueY + sinA * testRadius2).toInt()

            if (x1 in 0 until bitmap.width && y1 in 0 until bitmap.height &&
                x2 in 0 until bitmap.width && y2 in 0 until bitmap.height) {

                val p1 = bitmap.getPixel(x1, y1)
                val p2 = bitmap.getPixel(x2, y2)

                val isWhite1 = Color.red(p1) > 210 && Color.green(p1) > 210 && Color.blue(p1) > 210
                val isWhite2 = Color.red(p2) > 210 && Color.green(p2) > 210 && Color.blue(p2) > 210

                if (isWhite1 && isWhite2) {
                    val score = (Color.red(p1) + Color.red(p2))
                    if (score > bestScore) {
                        bestScore = score
                        bestAngle = angle
                    }
                }
            }
        }

        return bestAngle
    }
}
