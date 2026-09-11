package com.xndroid.eightballpool

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AimLineService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: AimOverlayView? = null
    private var windowManager: WindowManager? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var captureJob: Job? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private var captureWidth = 540
    private var captureHeight = 960

    private lateinit var ballDetector: BallDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ballDetector = BallDetector()
        getScreenMetrics()
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground immediately to avoid ForegroundServiceDidNotStartInTimeException
        startForegroundNotification()

        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) 
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            startScreenCapture(resultCode, data)
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        serviceScope.cancel()
        removeOverlay()
        stopScreenCapture()
        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        mediaProjection = null
    }

    private fun getScreenMetrics() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = windowManager?.currentWindowMetrics
                val bounds = windowMetrics?.bounds
                screenWidth = bounds?.width() ?: 1080
                screenHeight = bounds?.height() ?: 1920
                screenDensity = resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager?.defaultDisplay
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)
                screenWidth = metrics.widthPixels
                screenHeight = metrics.heightPixels
                screenDensity = metrics.densityDpi
            }
        } catch (e: Exception) {
            screenWidth = 1080
            screenHeight = 1920
            screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }

        captureWidth = (screenWidth / 2).coerceAtLeast(360)
        captureHeight = (screenHeight / 2).coerceAtLeast(640)
        ballDetector.width = screenWidth
        ballDetector.height = screenHeight
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        try {
            overlayView = AimOverlayView(this)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )

            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            // Ignore
        }
        overlayView = null
    }

    private fun startForegroundNotification() {
        val channelId = "aim_line_service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Aim Line Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("8BP Aim Assist Active")
            .setContentText("Drawing aim line overlay")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            startForeground(1, notification)
        }
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            // Android 14+ requires registering a callback before creating virtual display
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopScreenCapture()
                }
            }, null)

            imageReader = ImageReader.newInstance(
                captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2
            )

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AimAssist",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, null
            )

            startCaptureLoop()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopScreenCapture() {
        try {
            captureJob?.cancel()
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            val scaleX = screenWidth.toFloat() / captureWidth.toFloat()
            val scaleY = screenHeight.toFloat() / captureHeight.toFloat()

            while (isActive) {
                try {
                    val image = imageReader?.acquireLatestImage()
                    if (image != null) {
                        try {
                            val bitmap = imageToBitmap(image)
                            if (bitmap != null) {
                                val detectionResult = ballDetector.detect(bitmap, scaleX, scaleY)
                                overlayView?.updateDetection(detectionResult)
                                bitmap.recycle()
                            }
                        } finally {
                            image.close()
                        }
                    }
                } catch (e: Exception) {
                    // Prevent frame crashes
                }
                delay(66) // ~15fps is optimal for battery and CPU
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * captureWidth

            val fullWidth = captureWidth + rowPadding / pixelStride
            val bitmap = Bitmap.createBitmap(
                fullWidth,
                captureHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, captureWidth, captureHeight)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            null
        }
    }

    inner class AimOverlayView(context: Context) : View(context) {

        init {
            // Needed for BlurMaskFilter on hardware accelerated canvas
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        private val aimLinePaint = Paint().apply {
            color = Color.RED
            strokeWidth = 5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val aimLineGlowPaint = Paint().apply {
            color = Color.argb(120, 255, 60, 60)
            strokeWidth = 14f
            style = Paint.Style.STROKE
            isAntiAlias = true
            maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        }

        private val ballPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 36f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        private val pathPaint = Paint().apply {
            color = Color.YELLOW
            strokeWidth = 3f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
            isAntiAlias = true
        }

        private val pocketPaint = Paint().apply {
            color = Color.YELLOW
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        private var detectionResult: BallDetector.DetectionResult? = null

        fun updateDetection(result: BallDetector.DetectionResult) {
            detectionResult = result
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val result = detectionResult ?: return

            // Draw detected balls
            for (ball in result.balls) {
                drawBall(canvas, ball)
            }

            // Draw aim line if cue ball found
            if (result.aimLine != null) {
                drawAimLine(canvas, result.aimLine!!)
            }

            drawDebugInfo(canvas, result)
        }

        private fun drawBall(canvas: Canvas, ball: BallDetector.BallInfo) {
            val radius = ball.radius.coerceAtLeast(16f)

            when (ball.type) {
                BallDetector.BallType.CUE -> {
                    ballPaint.color = Color.WHITE
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)

                    ballPaint.color = Color.GRAY
                    ballPaint.style = Paint.Style.STROKE
                    ballPaint.strokeWidth = 3f
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
                BallDetector.BallType.EIGHT -> {
                    ballPaint.color = Color.BLACK
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)

                    val numberPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = radius * 1.1f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("8", ball.x, ball.y + radius * 0.4f, numberPaint)
                }
                BallDetector.BallType.SOLID -> {
                    ballPaint.color = ball.color
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
                BallDetector.BallType.STRIPE -> {
                    ballPaint.color = Color.WHITE
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)

                    ballPaint.color = ball.color
                    val stripeRect = RectF(
                        ball.x - radius,
                        ball.y - radius * 0.4f,
                        ball.x + radius,
                        ball.y + radius * 0.4f
                    )
                    canvas.drawRect(stripeRect, ballPaint)
                }
                BallDetector.BallType.UNKNOWN -> {
                    ballPaint.color = Color.LTGRAY
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
            }
        }

        private fun drawAimLine(canvas: Canvas, aimLine: BallDetector.AimLine) {
            // Glow line
            canvas.drawLine(
                aimLine.startX, aimLine.startY,
                aimLine.endX, aimLine.endY,
                aimLineGlowPaint
            )

            // Center solid line
            canvas.drawLine(
                aimLine.startX, aimLine.startY,
                aimLine.endX, aimLine.endY,
                aimLinePaint
            )

            // Target path to pocket
            if (aimLine.targetPath != null) {
                canvas.drawLine(
                    aimLine.targetPath!!.startX, aimLine.targetPath!!.startY,
                    aimLine.targetPath!!.endX, aimLine.targetPath!!.endY,
                    pathPaint
                )
            }

            // Pocket indicator
            if (aimLine.pocketX != null && aimLine.pocketY != null) {
                canvas.drawCircle(aimLine.pocketX!!, aimLine.pocketY!!, 26f, pocketPaint)
            }
        }

        private fun drawDebugInfo(canvas: Canvas, result: BallDetector.DetectionResult) {
            textPaint.textSize = 28f
            textPaint.color = Color.GREEN
            canvas.drawText("Balls: ${result.balls.size}", 30f, 100f, textPaint)

            if (result.aimLine != null) {
                textPaint.color = Color.RED
                val angle = Math.toDegrees(
                    Math.atan2(
                        (result.aimLine!!.endY - result.aimLine!!.startY).toDouble(),
                        (result.aimLine!!.endX - result.aimLine!!.startX).toDouble()
                    )
                )
                canvas.drawText("Aim: ${angle.toInt()}°", 30f, 135f, textPaint)
            }
        }
    }
}
