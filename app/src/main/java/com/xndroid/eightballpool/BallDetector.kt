package com.xndroid.eightballpool

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

class BallDetector {

    enum class BallType {
        CUE,        // White cue ball
        EIGHT,      // Black 8-ball
        SOLID,      // Solid colored balls (1-7)
        STRIPE,     // Striped balls (9-15)
        UNKNOWN
    }

    data class BallInfo(
        val x: Float,
        val y: Float,
        val radius: Float,
        val color: Int,
        val type: BallType,
        val confidence: Float
    )

    data class AimLine(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val targetPath: TargetPath?,
        val pocketX: Float?,
        val pocketY: Float?
    )

    data class TargetPath(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float
    )

    data class DetectionResult(
        val balls: List<BallInfo>,
        val aimLine: AimLine?,
        val timestamp: Long
    )

    // Pocket positions (normalized 0-1)
    private val pockets = listOf(
        Pair(0.06f, 0.08f),    // Top-left
        Pair(0.50f, 0.06f),    // Top-center
        Pair(0.94f, 0.08f),    // Top-right
        Pair(0.06f, 0.92f),    // Bottom-left
        Pair(0.50f, 0.94f),    // Bottom-center
        Pair(0.94f, 0.92f)     // Bottom-right
    )

    var width: Int = 1080
    var height: Int = 1920

    fun detect(bitmap: Bitmap, scaleX: Float = 1f, scaleY: Float = 1f): DetectionResult {
        val balls = detectBalls(bitmap, scaleX, scaleY)
        val cueBall = balls.find { it.type == BallType.CUE }
        val aimLine = if (cueBall != null) calculateAimLine(cueBall, balls) else null

        return DetectionResult(
            balls = balls,
            aimLine = aimLine,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun detectBalls(bitmap: Bitmap, scaleX: Float, scaleY: Float): List<BallInfo> {
        val balls = mutableListOf<BallInfo>()
        val bWidth = bitmap.width
        val bHeight = bitmap.height

        // Only scan table area (middle 85%)
        val startX = (bWidth * 0.05f).toInt()
        val endX = (bWidth * 0.95f).toInt()
        val startY = (bHeight * 0.10f).toInt()
        val endY = (bHeight * 0.90f).toInt()

        val sampleStep = 6
        val candidates = ArrayList<Triple<Int, Int, Int>>(300)

        // Fast pixel scan with max cap
        scanLoop@ for (y in startY until endY step sampleStep) {
            for (x in startX until endX step sampleStep) {
                if (candidates.size >= 250) break@scanLoop

                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Filter out green table felt
                if (isTableGreen(r, g, b)) continue

                if (isWhite(r, g, b)) {
                    candidates.add(Triple(x, y, Color.WHITE))
                } else if (isBlack(r, g, b)) {
                    candidates.add(Triple(x, y, Color.BLACK))
                } else if (isColoredBall(r, g, b)) {
                    candidates.add(Triple(x, y, pixel))
                }
            }
        }

        if (candidates.isEmpty()) return balls

        // Grid clustering to prevent O(N^2) explosion
        val clusterDist = (bWidth * 0.04f).coerceAtLeast(12f)
        val clusters = clusterFast(candidates, clusterDist)

        for (cluster in clusters) {
            if (cluster.size < 3) continue

            var sumX = 0f
            var sumY = 0f
            for (item in cluster) {
                sumX += item.first
                sumY += item.second
            }
            val avgX = sumX / cluster.size
            val avgY = sumY / cluster.size
            val avgColor = cluster[0].third

            val screenX = avgX * scaleX
            val screenY = avgY * scaleY
            val radius = (clusterDist * scaleX * 0.9f).coerceIn(16f, 40f)

            val type = when {
                avgColor == Color.WHITE -> BallType.CUE
                avgColor == Color.BLACK -> BallType.EIGHT
                cluster.any { it.third == Color.WHITE } && cluster.any { it.third != Color.WHITE && it.third != Color.BLACK } -> BallType.STRIPE
                else -> BallType.SOLID
            }

            balls.add(BallInfo(
                x = screenX,
                y = screenY,
                radius = radius,
                color = avgColor,
                type = type,
                confidence = 0.9f
            ))

            if (balls.size >= 16) break
        }

        return balls
    }

    private fun isTableGreen(r: Int, g: Int, b: Int): Boolean {
        // Typical 8BP felt green: green dominant, moderate brightness
        return g > r + 15 && g > b + 10 && g in 40..170
    }

    private fun isWhite(r: Int, g: Int, b: Int): Boolean {
        return r > 215 && g > 215 && b > 215
    }

    private fun isBlack(r: Int, g: Int, b: Int): Boolean {
        return r < 35 && g < 35 && b < 35
    }

    private fun isColoredBall(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max > 0) (max - min).toFloat() / max else 0f
        return saturation > 0.45f && max > 100
    }

    private fun clusterFast(candidates: List<Triple<Int, Int, Int>>, maxDist: Float): List<List<Triple<Int, Int, Int>>> {
        val clusters = mutableListOf<MutableList<Triple<Int, Int, Int>>>()
        val maxDistSq = maxDist * maxDist

        for (cand in candidates) {
            var added = false
            for (cluster in clusters) {
                val center = cluster[0]
                val dx = cand.first - center.first
                val dy = cand.second - center.second
                if (dx * dx + dy * dy < maxDistSq) {
                    cluster.add(cand)
                    added = true
                    break
                }
            }
            if (!added && clusters.size < 20) {
                clusters.add(mutableListOf(cand))
            }
        }

        return clusters
    }

    private fun calculateAimLine(cueBall: BallInfo, allBalls: List<BallInfo>): AimLine? {
        val targetBall = findBestTarget(cueBall, allBalls) ?: return null

        val dx = targetBall.x - cueBall.x
        val dy = targetBall.y - cueBall.y
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())

        val lineLength = 900f
        val endX = cueBall.x + (Math.cos(angle) * lineLength).toFloat()
        val endY = cueBall.y + (Math.sin(angle) * lineLength).toFloat()

        val pocket = findBestPocket(targetBall)

        val targetPath = if (pocket != null) {
            val pX = pocket.first * width
            val pY = pocket.second * height
            TargetPath(
                startX = targetBall.x,
                startY = targetBall.y,
                endX = pX,
                endY = pY
            )
        } else null

        return AimLine(
            startX = cueBall.x,
            startY = cueBall.y,
            endX = endX,
            endY = endY,
            targetPath = targetPath,
            pocketX = pocket?.first?.let { it * width },
            pocketY = pocket?.second?.let { it * height }
        )
    }

    private fun findBestTarget(cueBall: BallInfo, allBalls: List<BallInfo>): BallInfo? {
        var bestBall: BallInfo? = null
        var minDistance = Float.MAX_VALUE

        for (ball in allBalls) {
            if (ball.type == BallType.CUE) continue

            val dx = ball.x - cueBall.x
            val dy = ball.y - cueBall.y
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            if (dist > 20f && dist < minDistance) {
                minDistance = dist
                bestBall = ball
            }
        }

        return bestBall
    }

    private fun findBestPocket(ball: BallInfo): Pair<Float, Float>? {
        var bestPocket: Pair<Float, Float>? = null
        var bestDist = Float.MAX_VALUE

        for (pocket in pockets) {
            val px = pocket.first * width
            val py = pocket.second * height

            val dx = ball.x - px
            val dy = ball.y - py
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

            if (dist < bestDist) {
                bestDist = dist
                bestPocket = pocket
            }
        }

        return bestPocket
    }
}
