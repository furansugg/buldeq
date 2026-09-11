package com.xndroid.eightballpool

import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import android.widget.FrameLayout
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
    private var screenDensity = 1

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
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED) 
            ?: Activity.RESULT_CANCELED
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            startForegroundNotification()
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
        mediaProjection?.stop()
    }

    private fun getScreenMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager?.currentWindowMetrics
            val bounds = windowMetrics?.bounds
            screenWidth = bounds?.width() ?: 1080
            screenHeight = bounds?.height() ?: 1920
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
    }

    private fun createOverlay() {
        overlayView = AimOverlayView(this)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        windowManager?.addView(overlayView, params)
    }

    private fun removeOverlay() {
        overlayView?.let {
            windowManager?.removeView(it)
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
            manager.createNotificationChannel(channel)
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

        startForeground(1, notification)
    }

    private fun startScreenCapture(resultCode: Int, data: Intent) {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AimAssist",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )

        startCaptureLoop()
    }

    private fun stopScreenCapture() {
        captureJob?.cancel()
        virtualDisplay?.release()
        imageReader?.close()
    }

    private fun startCaptureLoop() {
        captureJob = serviceScope.launch {
            while (isActive) {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    try {
                        val bitmap = imageToBitmap(image)
                        if (bitmap != null) {
                            val detectionResult = ballDetector.detect(bitmap)
                            overlayView?.updateDetection(detectionResult)
                            bitmap.recycle()
                        }
                    } finally {
                        image.close()
                    }
                }
                delay(33) // ~30fps
            }
        }
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop to screen size
            Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight).also {
                if (it != bitmap) bitmap.recycle()
            }
        } catch (e: Exception) {
            null
        }
    }

    inner class AimOverlayView(context: Context) : View(context) {

        private val aimLinePaint = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val aimLineGlowPaint = Paint().apply {
            color = Color.argb(100, 255, 50, 50)
            strokeWidth = 12f
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

        private val cueBallPaint = Paint().apply {
            color = Color.WHITE
            isAntiAlias = true
            style = Paint.Style.FILL
        }

        private val eightBallPaint = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            style = Paint.Style.FILL
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

            // Draw aim line if we have cue ball and a valid aim
            if (result.aimLine != null) {
                drawAimLine(canvas, result.aimLine!!)
            }

            // Draw debug info
            drawDebugInfo(canvas, result)
        }

        private fun drawBall(canvas: Canvas, ball: BallDetector.BallInfo) {
            val radius = ball.radius * 1.5f

            when (ball.type) {
                BallDetector.BallType.CUE -> {
                    ballPaint.color = Color.WHITE
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                    
                    // Draw outline
                    ballPaint.color = Color.GRAY
                    ballPaint.style = Paint.Style.STROKE
                    ballPaint.strokeWidth = 2f
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
                BallDetector.BallType.EIGHT -> {
                    ballPaint.color = Color.BLACK
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                    
                    // Draw "8" text
                    val textPaint = Paint().apply {
                        color = Color.WHITE
                        textSize = radius * 1.2f
                        textAlign = Paint.Align.CENTER
                        isAntiAlias = true
                    }
                    canvas.drawText("8", ball.x, ball.y + radius * 0.4f, textPaint)
                }
                BallDetector.BallType.SOLID -> {
                    ballPaint.color = ball.color
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
                BallDetector.BallType.STRIPE -> {
                    // Draw white base
                    ballPaint.color = Color.WHITE
                    ballPaint.style = Paint.Color.WHITE
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                    
                    // Draw colored stripe
                    ballPaint.color = ball.color
                    ballPaint.style = Paint.Style.FILL
                    val stripeRect = RectF(
                        ball.x - radius,
                        ball.y - radius * 0.4f,
                        ball.x + radius,
                        ball.y + radius * 0.4f
                    )
                    canvas.drawRect(stripeRect, ballPaint)
                }
                BallDetector.BallType.UNKNOWN -> {
                    ballPaint.color = Color.GRAY
                    ballPaint.style = Paint.Style.FILL
                    canvas.drawCircle(ball.x, ball.y, radius, ballPaint)
                }
            }
        }

        private fun drawAimLine(canvas: Canvas, aimLine: BallDetector.AimLine) {
            // Draw glow
            canvas.drawLine(
                aimLine.startX, aimLine.startY,
                aimLine.endX, aimLine.endY,
                aimLineGlowPaint
            )

            // Draw main line
            canvas.drawLine(
                aimLine.startX, aimLine.startY,
                aimLine.endX, aimLine.endY,
                aimLinePaint
            )

            // Draw predicted path for target ball to pocket
            if (aimLine.targetPath != null) {
                val pathPaint = Paint().apply {
                    color = Color.YELLOW
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                    pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
                    isAntiAlias = true
                }
                canvas.drawLine(
                    aimLine.targetPath!!.startX, aimLine.targetPath!!.startY,
                    aimLine.targetPath!!.endX, aimLine.targetPath!!.endY,
                    pathPaint
                )
            }

            // Draw pocket indicator
            if (aimLine.pocketX != null && aimLine.pocketY != null) {
                val pocketPaint = Paint().apply {
                    color = Color.YELLOW
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                canvas.drawCircle(aimLine.pocketX!!, aimLine.pocketY!!, 30f, pocketPaint)
            }
        }

        private fun drawDebugInfo(canvas: Canvas, result: BallDetector.DetectionResult) {
            val y = 100f
            textPaint.textSize = 32f
            textPaint.color = Color.GREEN
            canvas.drawText("Balls detected: ${result.balls.size}", 20f, y, textPaint)
            
            if (result.aimLine != null) {
                textPaint.color = Color.RED
                val angle = Math.toDegrees(
                    Math.atan2(
                        (result.aimLine!!.endY - result.aimLine!!.startY).toDouble(),
                        (result.aimLine!!.endX - result.aimLine!!.startX).toDouble()
                    )
                )
                canvas.drawText("Aim angle: ${angle.toInt()}°", 20f, y + 40f, textPaint)
            }
        }
    }
}
