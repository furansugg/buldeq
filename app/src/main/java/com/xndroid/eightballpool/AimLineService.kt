package com.xndroid.eightballpool

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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

    private var screenWidth = 1920
    private var screenHeight = 1080
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    private var captureWidth = 960
    private var captureHeight = 540

    private lateinit var ballDetector: BallDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        ballDetector = BallDetector()
        getScreenMetrics()
        createOverlay()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        getScreenMetrics()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                val rawW = bounds?.width() ?: 1920
                val rawH = bounds?.height() ?: 1080
                screenWidth = maxOf(rawW, rawH)
                screenHeight = minOf(rawW, rawH)
                screenDensity = resources.displayMetrics.densityDpi
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager?.defaultDisplay
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                display?.getRealMetrics(metrics)
                val rawW = metrics.widthPixels
                val rawH = metrics.heightPixels
                screenWidth = maxOf(rawW, rawH)
                screenHeight = minOf(rawW, rawH)
                screenDensity = metrics.densityDpi
            }
        } catch (e: Exception) {
            screenWidth = 1920
            screenHeight = 1080
            screenDensity = DisplayMetrics.DENSITY_DEFAULT
        }

        captureWidth = (screenWidth / 2).coerceAtLeast(640)
        captureHeight = (screenHeight / 2).coerceAtLeast(360)
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
            .setContentText("Drawing 3-line aim assist overlay")
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
            while (isActive) {
                try {
                    val scaleX = screenWidth.toFloat() / captureWidth.toFloat()
                    val scaleY = screenHeight.toFloat() / captureHeight.toFloat()

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
                delay(50) // ~20fps smooth tracking
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
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        // Cue to ghost line
        private val cueLinePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 4.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val cueLineGlowPaint = Paint().apply {
            color = Color.argb(100, 255, 255, 255)
            strokeWidth = 12f
            style = Paint.Style.STROKE
            isAntiAlias = true
            maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        }

        // Ghost ball ring
        private val ghostBallPaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 3.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val ghostBallInnerPaint = Paint().apply {
            color = Color.argb(80, 255, 255, 255)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Target ball trajectory line (extended + bounce)
        private val targetLinePaint = Paint().apply {
            color = Color.rgb(255, 60, 60) // Laser Red
            strokeWidth = 4.5f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val targetLineBouncePaint = Paint().apply {
            color = Color.rgb(255, 120, 60) // Orange on cushion bounce
            strokeWidth = 4.0f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)
            isAntiAlias = true
        }

        // Cue deflection line (90-degree tangent)
        private val cueDeflectPaint = Paint().apply {
            color = Color.rgb(80, 220, 255) // Cyan deflection
            strokeWidth = 4.0f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        private val bouncePointPaint = Paint().apply {
            color = Color.YELLOW
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Pocket indicator
        private val pocketGlowPaint = Paint().apply {
            color = Color.argb(160, 50, 255, 50) // Green target glow
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
            maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        }

        private val pocketRingPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = 28f
            isAntiAlias = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        private var detectionResult: BallDetector.DetectionResult? = null

        fun updateDetection(result: BallDetector.DetectionResult) {
            detectionResult = result
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val result = detectionResult ?: return
            val sol = result.solution

            if (sol != null) {
                // 1. Draw Cue to Ghost line (with subtle glow)
                canvas.drawLine(
                    sol.cueToGhostLine.startX, sol.cueToGhostLine.startY,
                    sol.cueToGhostLine.endX, sol.cueToGhostLine.endY,
                    cueLineGlowPaint
                )
                canvas.drawLine(
                    sol.cueToGhostLine.startX, sol.cueToGhostLine.startY,
                    sol.cueToGhostLine.endX, sol.cueToGhostLine.endY,
                    cueLinePaint
                )

                // 2. Draw Ghost Ball (circle at impact position)
                canvas.drawCircle(sol.ghostX, sol.ghostY, sol.ballRadius, ghostBallInnerPaint)
                canvas.drawCircle(sol.ghostX, sol.ghostY, sol.ballRadius, ghostBallPaint)

                // Crosshair in ghost ball
                val crossR = sol.ballRadius * 0.4f
                canvas.drawLine(sol.ghostX - crossR, sol.ghostY, sol.ghostX + crossR, sol.ghostY, ghostBallPaint)
                canvas.drawLine(sol.ghostX, sol.ghostY - crossR, sol.ghostX, sol.ghostY + crossR, ghostBallPaint)

                // 3. Draw Extended Target Ball Path (with cushion bounces)
                for (seg in sol.targetBallPath) {
                    val paint = if (seg.isBounce) targetLineBouncePaint else targetLinePaint
                    canvas.drawLine(seg.startX, seg.startY, seg.endX, seg.endY, paint)

                    if (seg.isBounce) {
                        canvas.drawCircle(seg.startX, seg.startY, 6f, bouncePointPaint)
                    }
                }

                // 4. Draw Cue Ball Deflection Path (90-degree tangent)
                for (seg in sol.cueDeflectionPath) {
                    canvas.drawLine(seg.startX, seg.startY, seg.endX, seg.endY, cueDeflectPaint)
                    if (seg.isBounce) {
                        canvas.drawCircle(seg.startX, seg.startY, 5f, bouncePointPaint)
                    }
                }

                // 5. Highlight Target Pocket if on target
                if (sol.targetPocket != null) {
                    canvas.drawCircle(sol.targetPocket.x, sol.targetPocket.y, sol.targetPocket.radius, pocketGlowPaint)
                    canvas.drawCircle(sol.targetPocket.x, sol.targetPocket.y, sol.targetPocket.radius, pocketRingPaint)
                }
            }

            // Top Status Badge
            drawStatusBadge(canvas, sol != null)
        }

        private fun drawStatusBadge(canvas: Canvas, active: Boolean) {
            textPaint.color = if (active) Color.GREEN else Color.argb(180, 255, 180, 50)
            val status = if (active) "8BP AIM: LOCKED (3 LINES + BANK)" else "8BP AIM: SCANNING..."
            canvas.drawText(status, 40f, 60f, textPaint)
        }
    }
}
