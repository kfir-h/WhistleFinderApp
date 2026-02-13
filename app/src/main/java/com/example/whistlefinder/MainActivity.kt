package com.example.whistlefinder

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var serviceStatusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var scheduleButton: Button
    private lateinit var customPatternButton: Button
    private lateinit var vibrationSwitch: SwitchCompat
    private lateinit var sensitivitySeekBar: SeekBar
    private lateinit var sensitivityText: TextView
    private lateinit var scheduleStatusText: TextView
    private lateinit var customPatternStatusText: TextView
    private lateinit var prefs: SharedPreferences

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SharedPreferences
        prefs = getSharedPreferences("WhistleFinderPrefs", MODE_PRIVATE)

        // Initialize UI elements
        serviceStatusText = findViewById(R.id.serviceStatusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        scheduleButton = findViewById(R.id.scheduleButton)
        customPatternButton = findViewById(R.id.customPatternButton)
        vibrationSwitch = findViewById(R.id.vibrationSwitch)
        sensitivitySeekBar = findViewById(R.id.sensitivitySeekBar)
        sensitivityText = findViewById(R.id.sensitivityText)
        scheduleStatusText = findViewById(R.id.scheduleStatusText)
        customPatternStatusText = findViewById(R.id.customPatternStatusText)

        // Load saved settings
        vibrationSwitch.isChecked = prefs.getBoolean("vibration_enabled", true)
        val sensitivity = prefs.getInt("sensitivity", 50)
        sensitivitySeekBar.progress = sensitivity
        sensitivityText.text = "Sensitivity: $sensitivity%"

        // Set up listeners
        startButton.setOnClickListener {
            if (checkPermissions()) {
                startWhistleService()
            } else {
                requestPermissions()
            }
        }

        stopButton.setOnClickListener {
            stopWhistleService()
        }

        scheduleButton.setOnClickListener {
            val intent = Intent(this, ScheduleActivity::class.java)
            startActivity(intent)
        }
        
        customPatternButton.setOnClickListener {
            val intent = Intent(this, CustomPatternActivity::class.java)
            startActivity(intent)
        }

        vibrationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("vibration_enabled", isChecked).apply()
        }

        sensitivitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                sensitivityText.text = "Sensitivity: $progress%"
                prefs.edit().putInt("sensitivity", progress).apply()
                
                // Update detection threshold in real-time if service is running
                if (WhistleDetectionService.isServiceRunning) {
                    WhistleDetectionService.updateSensitivity(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        updateServiceStatus()
        updateScheduleStatus()
        updateCustomPatternStatus()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateScheduleStatus()
        updateCustomPatternStatus()
    }

    private fun checkPermissions(): Boolean {
        val micPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return micPermission && notificationPermission
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.SCHEDULE_EXACT_ALARM)
        }

        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startWhistleService()
            } else {
                Toast.makeText(
                    this,
                    "Permissions are required for whistle detection",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startWhistleService() {
        val intent = Intent(this, WhistleDetectionService::class.java)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateServiceStatus()
        Toast.makeText(this, "Whistle detection started", Toast.LENGTH_SHORT).show()
    }

    private fun stopWhistleService() {
        val intent = Intent(this, WhistleDetectionService::class.java)
        stopService(intent)
        updateServiceStatus()
        Toast.makeText(this, "Whistle detection stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatus() {
        val isRunning = WhistleDetectionService.isServiceRunning
        
        if (isRunning) {
            serviceStatusText.text = "🎤 ACTIVE - Listening for whistles"
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            startButton.isEnabled = false
            stopButton.isEnabled = true
        } else {
            serviceStatusText.text = "⭕ INACTIVE"
            serviceStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            startButton.isEnabled = true
            stopButton.isEnabled = false
        }
    }

    private fun updateScheduleStatus() {
        val scheduleEnabled = prefs.getBoolean("schedule_enabled", false)
        
        if (scheduleEnabled) {
            val startHour = prefs.getInt("start_hour", 18)
            val startMinute = prefs.getInt("start_minute", 0)
            val endHour = prefs.getInt("end_hour", 23)
            val endMinute = prefs.getInt("end_minute", 0)
            
            val startTime = String.format("%02d:%02d", startHour, startMinute)
            val endTime = String.format("%02d:%02d", endHour, endMinute)
            
            scheduleStatusText.text = "⏰ Scheduled: $startTime - $endTime daily"
            scheduleStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        } else {
            scheduleStatusText.text = "⏰ No schedule set"
            scheduleStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
    
    private fun updateCustomPatternStatus() {
        val customEnabled = prefs.getBoolean("custom_pattern_enabled", false)
        
        if (customEnabled) {
            val patternName = prefs.getString("custom_pattern_name", "Unknown")
            val patternType = prefs.getString("custom_pattern_type", "whistle")
            
            val typeIcon = when(patternType) {
                "whistle" -> "🎵"
                "voice" -> "🗣️"
                "both" -> "🎵🗣️"
                else -> "🎵"
            }
            
            customPatternStatusText.text = "$typeIcon Custom: $patternName"
            customPatternStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_purple))
        } else {
            customPatternStatusText.text = "🎵 Default whistle detection"
            customPatternStatusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
}
