package com.xndroid.eightballpool

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.xndroid.eightballpool.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (Settings.canDrawOverlays(this)) {
            requestScreenCapture()
        } else {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAimLineService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            checkOverlayPermission()
        } else {
            Toast.makeText(this, "Notification permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupUI() {
        binding.btnToggle.setOnClickListener {
            if (isServiceRunning) {
                stopAimLineService()
            } else {
                startPermissionFlow()
            }
        }

        binding.btnSettings.setOnClickListener {
            // Open overlay settings
            val intent = Intent(this, AimLineSettingsActivity::class.java)
            startActivity(intent)
        }

        binding.btnAbout.setOnClickListener {
            Toast.makeText(this, "8BP Aim Assist v1.0\nFor educational purposes only", 
                Toast.LENGTH_LONG).show()
        }
    }

    private fun startPermissionFlow() {
        // Step 1: Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        checkOverlayPermission()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun startAimLineService(resultCode: Int, data: Intent) {
        try {
            val serviceIntent = Intent(this, AimLineService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            isServiceRunning = true
            updateUI()
            Toast.makeText(this, "Aim assist started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopAimLineService() {
        val serviceIntent = Intent(this, AimLineService::class.java)
        stopService(serviceIntent)

        isServiceRunning = false
        updateUI()
        Toast.makeText(this, "Aim assist stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateUI() {
        if (isServiceRunning) {
            binding.btnToggle.text = "STOP AIM ASSIST"
            binding.statusText.text = "Status: ACTIVE"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.ball_indicator))
        } else {
            binding.btnToggle.text = "START AIM ASSIST"
            binding.statusText.text = "Status: INACTIVE"
            binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
