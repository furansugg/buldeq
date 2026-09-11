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

    // Known ball colors (HSV ranges)
    private val ballColors = mapOf(
        // Ball ID to Hue range
        1 to floatArrayOf(0f, 10f),        // Yellow
        2 to floatArrayOf(200f, 260f),     // Blue
        3 to floatArrayOf(100f, 140f),     // Red
        4 to floatArrayOf(270f, 330f),     // Purple
        5 to floatArrayOf(20f, 50f),       // Orange
        6 to floatArrayOf(120f, 160f),     // Green
        7 to floatArrayOf(330f, 360f),     // Maroon
        9 to floatArrayOf(0f, 10f),        // Yellow stripe
        10 to floatArrayOf(200f, 260f),    // Blue stripe
        11 to floatArrayOf(100f, 140f),    // Red stripe
        12 to floatArrayOf(270f, 330f),    // Purple stripe
        13 to floatArrayOf(20f, 50f),      // Orange stripe
        14 to floatArrayOf(120f, 160f),    // Green stripe
        15 to floatArrayOf(330f, 360f)     // Maroon stripe
    )

    // Pocket positions (normalized 0-1)
    private val pockets = listOf(
        Pair(0.05f, 0.05f),    // Top-left
        Pair(0.5f, 0.03f),     // Top-center
        Pair(0.95f, 0.05f),    // Top-right
        Pair(0.05f, 0.95f),    // Bottom-left
        Pair(0.5f, 0.97f),     // Bottom-center
        Pair(0.95f, 0.95f)     // Bottom-right
    )

    private var lastCueBall: BallInfo? = null
    private var lastBalls = listOf<BallInfo>()

    fun detect(bitmap: Bitmap): DetectionResult {
        val balls = detectBalls(bitmap)
        val cueBall = balls.find { it.type == BallType.CUE }
        val aimLine = if (cueBall != null) calculateAimLine(cueBall, balls) else null

        // Update state
        lastCueBall = cueBall
        lastBalls = balls

        return DetectionResult(
            balls = balls,
            aimLine = aimLine,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun detectBalls(bitmap: Bitmap): List<BallInfo> {
        val balls = mutableListOf<BallInfo>()
        val width = bitmap.width
        val height = bitmap.height

        // Sample pixels to find ball centers
        val sampleSize = 4
        val ballCandidates = mutableListOf<Triple<Int, Int, Int>>() // x, y, color

        // Scan for white regions (cue ball)
        for (y in 0 until height step sampleSize) {
            for (x in 0 until width step sampleSize) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // Check for white (cue ball)
                if (isWhite(r, g, b)) {
                    ballCandidates.add(Triple(x, y, Color.WHITE))
                }
                // Check for black (8 ball)
                else if (isBlack(r, g, b)) {
                    ballCandidates.add(Triple(x, y, Color.BLACK))
                }
                // Check for colored balls
                else if (isColored(r, g, b)) {
                    ballCandidates.add(Triple(x, y, pixel))
                }
            }
        }

        // Cluster nearby candidates into balls
        val clustered = clusterBalls(ballCandidates, width, height)

        // Create BallInfo objects
        for (cluster in clustered) {
            val avgX = cluster.map { it.first }.average().toFloat()
            val avgY = cluster.map { it.second }.average().toFloat()
            val avgColor = cluster.map { it.third }.average().toInt()
            val radius = calculateRadius(cluster, avgX, avgY)

            val type = when {
                avgColor == Color.WHITE -> BallType.CUE
                avgColor == Color.BLACK -> BallType.EIGHT
                isStriped(cluster, avgX, avgY, radius) -> BallType.STRIPE
                isColored(Color.red(avgColor), Color.green(avgColor), Color.blue(avgColor)) -> BallType.SOLID
                else -> BallType.UNKNOWN
            }

            balls.add(BallInfo(
                x = avgX,
                y = avgY,
                radius = radius,
                color = avgColor,
                type = type,
                confidence = cluster.size.toFloat() / 100f
            ))
        }

        return balls
    }

    private fun isWhite(r: Int, g: Int, b: Int): Boolean {
        return r > 200 && g > 200 && b > 200 && abs(r - g) < 30 && abs(g - b) < 30
    }

    private fun isBlack(r: Int, g: Int, b: Int): Boolean {
        return r < 50 && g < 50 && b < 50
    }

    private fun isColored(r: Int, g: Int, b: Int): Boolean {
        // Check if color is saturated enough to be a ball
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max > 0) (max - min).toFloat() / max else 0f

        return saturation > 0.3f && max > 80
    }

    private fun isStriped(cluster: List<Triple<Int, Int, Int>>, centerX: Float, centerY: Float, radius: Float): Boolean {
        // Check if the ball has both white and colored pixels
        var whiteCount = 0
        var coloredCount = 0

        for ((x, y, color) in cluster) {
            val dist = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toDouble())
            if (dist < radius * 1.2) {
                if (color == Color.WHITE) {
                    whiteCount++
                } else if (color != Color.BLACK) {
                    coloredCount++
                }
            }
        }

        // Striped balls have roughly equal white and colored pixels
        return whiteCount > 5 && coloredCount > 5 && abs(whiteCount - coloredCount) < whiteCount * 0.5
    }

    private fun clusterBalls(candidates: List<Triple<Int, Int, Int>>, width: Int, height: Int): List<List<Triple<Int, Int, Int>>> {
        val minClusterSize = 10
        val maxClusterDist = width * 0.05f // 5% of screen width

        val clusters = mutableListOf<MutableList<Triple<Int, Int, Int>>>()
        val used = BooleanArray(candidates.size)

        for (i in candidates.indices) {
            if (used[i]) continue

            val cluster = mutableListOf(candidates[i])
            used[i] = true

            for (j in i + 1 until candidates.size) {
                if (used[j]) continue

                val dist = sqrt(
                    ((candidates[i].first - candidates[j].first) * (candidates[i].first - candidates[j].first) +
                     (candidates[i].second - candidates[j].second) * (candidates[i].second - candidates[j].second)).toDouble()
                )

                if (dist < maxClusterDist) {
                    cluster.add(candidates[j])
                    used[j] = true
                }
            }

            if (cluster.size >= minClusterSize) {
                clusters.add(cluster)
            }
        }

        return clusters
    }

    private fun calculateRadius(cluster: List<Triple<Int, Int, Int>>, centerX: Float, centerY: Float): Float {
        var maxDist = 0f
        for ((x, y, _) in cluster) {
            val dist = sqrt(((x - centerX) * (x - centerX) + (y - centerY) * (y - centerY)).toFloat())
            if (dist > maxDist) maxDist = dist
        }
        return maxDist
    }

    private fun calculateAimLine(cueBall: BallInfo, allBalls: List<BallInfo>): AimLine? {
        // Find the best target ball
        val targetBall = findBestTarget(cueBall, allBalls) ?: return null

        // Calculate aim angle
        val dx = targetBall.x - cueBall.x
        val dy = targetBall.y - cueBall.y
        val angle = Math.atan2(dy.toDouble(), dx.toDouble())

        // Calculate extended aim line
        val lineLength = 800f // pixels
        val endX = cueBall.x + (Math.cos(angle) * lineLength).toFloat()
        val endY = cueBall.y + (Math.sin(angle) * lineLength).toFloat()

        // Find best pocket for this shot
        val pocket = findBestPocket(targetBall)

        // Calculate target ball path
        val targetPath = if (pocket != null) {
            TargetPath(
                startX = targetBall.x,
                startY = targetBall.y,
                endX = pocket.first * cueBall.x * 2, // Simplified
                endY = pocket.second * cueBall.y * 2
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
        var bestScore = -Float.MAX_VALUE

        for (ball in allBalls) {
            if (ball.type == BallType.CUE || ball.type == BallType.UNKNOWN) continue

            // Calculate score based on distance and angle
            val distToCue = sqrt(
                ((ball.x - cueBall.x) * (ball.x - cueBall.x) +
                 (ball.y - cueBall.y) * (ball.y - cueBall.y)).toDouble()
            ).toFloat()

            // Prefer closer balls
            val distanceScore = 1f / (distToCue + 1f)

            // Prefer balls that are not blocked by other balls
            val clearPath = hasClearPath(cueBall, ball, allBalls)
            val pathScore = if (clearPath) 1f else 0.3f

            val score = distanceScore * pathScore

            if (score > bestScore) {
                bestScore = score
                bestBall = ball
            }
        }

        return bestBall
    }

    private fun hasClearPath(from: BallInfo, to: BallInfo, allBalls: List<BallInfo>): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        for (ball in allBalls) {
            if (ball == from || ball == to) continue

            // Check if ball blocks the path
            val t = maxOf(0f, minOf(1f,
                ((ball.x - from.x) * dx + (ball.y - from.y) * dy) / (dist * dist)
            ))

            val projX = from.x + t * dx
            val projY = from.y + t * dy

            val distToLine = sqrt(
                ((ball.x - projX) * (ball.x - projX) +
                 (ball.y - projY) * (ball.y - projY)).toDouble()
            ).toFloat()

            if (distToLine < ball.radius + to.radius) {
                return false
            }
        }

        return true
    }

    private fun findBestPocket(ball: BallInfo): Pair<Float, Float>? {
        var bestPocket: Pair<Float, Float>? = null
        var bestDist = Float.MAX_VALUE

        for (pocket in pockets) {
            val pocketX = pocket.first * 1080 // Assume 1080 width
            val pocketY = pocket.second * 1920 // Assume 1920 height

            val dist = sqrt(
                ((ball.x - pocketX) * (ball.x - pocketX) +
                 (ball.y - pocketY) * (ball.y - pocketY)).toDouble()
            ).toFloat()

            if (dist < bestDist) {
                bestDist = dist
                bestPocket = pocket
            }
        }

        return bestPocket
    }

    // Public getters for settings
    var width: Int = 1080
    var height: Int = 1920
}
