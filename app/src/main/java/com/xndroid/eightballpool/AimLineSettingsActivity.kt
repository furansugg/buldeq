package com.xndroid.eightballpool

import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.xndroid.eightballpool.databinding.ActivitySettingsBinding

class AimLineSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSliders()
        setupButtons()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Aim Line Settings"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupSliders() {
        // Aim line length
        binding.sliderLineLength.apply {
            max = 100
            progress = 50 // Default 50%
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val length = 200f + (progress * 12f) // 200-1400 pixels
                    binding.textLineLength.text = "Line Length: ${length.toInt()}px"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // Line thickness
        binding.sliderLineThickness.apply {
            max = 20
            progress = 4 // Default 4px
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val thickness = 1f + (progress * 0.5f) // 1-11px
                    binding.textLineThickness.text = "Line Thickness: ${thickness}dp"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // Glow intensity
        binding.sliderGlow.apply {
            max = 10
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.textGlow.text = "Glow Intensity: ${progress * 10}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        // Detection sensitivity
        binding.sliderSensitivity.apply {
            max = 10
            progress = 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    binding.textSensitivity.text = "Sensitivity: ${progress * 10}%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            // Save settings to SharedPreferences
            val prefs = getSharedPreferences("aim_settings", MODE_PRIVATE)
            prefs.edit().apply {
                putFloat("line_length", 200f + (binding.sliderLineLength.progress * 12f))
                putFloat("line_thickness", 1f + (binding.sliderLineThickness.progress * 0.5f))
                putInt("glow_intensity", binding.sliderGlow.progress)
                putInt("sensitivity", binding.sliderSensitivity.progress)
                apply()
            }
            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnReset.setOnClickListener {
            binding.sliderLineLength.progress = 50
            binding.sliderLineThickness.progress = 4
            binding.sliderGlow.progress = 5
            binding.sliderSensitivity.progress = 5
            Toast.makeText(this, "Settings reset to default", Toast.LENGTH_SHORT).show()
        }
    }
}
