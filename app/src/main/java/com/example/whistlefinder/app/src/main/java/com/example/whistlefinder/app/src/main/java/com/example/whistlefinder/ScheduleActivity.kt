package com.example.whistlefinder

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.*

class ScheduleActivity : AppCompatActivity() {
    
    private lateinit var scheduleSwitch: SwitchCompat
    private lateinit var startTimeButton: Button
    private lateinit var endTimeButton: Button
    private lateinit var saveButton: Button
    private lateinit var startTimeText: TextView
    private lateinit var endTimeText: TextView
    private lateinit var prefs: SharedPreferences
    
    private var startHour = 18
    private var startMinute = 0
    private var endHour = 23
    private var endMinute = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Schedule Settings"
        
        prefs = getSharedPreferences("WhistleFinderPrefs", MODE_PRIVATE)
        
        // Initialize views
        scheduleSwitch = findViewById(R.id.scheduleSwitch)
        startTimeButton = findViewById(R.id.startTimeButton)
        endTimeButton = findViewById(R.id.endTimeButton)
        saveButton = findViewById(R.id.saveButton)
        startTimeText = findViewById(R.id.startTimeText)
        endTimeText = findViewById(R.id.endTimeText)
        
        // Load saved schedule
        loadScheduleSettings()
        
        // Set up listeners
        scheduleSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateUIState(isChecked)
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                checkExactAlarmPermission()
            }
        }
        
        startTimeButton.setOnClickListener {
            showTimePickerDialog(true)
        }
        
        endTimeButton.setOnClickListener {
            showTimePickerDialog(false)
        }
        
        saveButton.setOnClickListener {
            saveSchedule()
        }
        
        // Quick preset buttons
        findViewById<Button>(R.id.presetSleepButton).setOnClickListener {
            setPresetTime(22, 0, 7, 0) // 10 PM - 7 AM
        }
        
        findViewById<Button>(R.id.presetWorkButton).setOnClickListener {
            setPresetTime(9, 0, 17, 0) // 9 AM - 5 PM
        }
        
        findViewById<Button>(R.id.presetEveningButton).setOnClickListener {
            setPresetTime(18, 0, 23, 0) // 6 PM - 11 PM
        }
        
        updateTimeDisplays()
        updateUIState(scheduleSwitch.isChecked)
    }

    private fun loadScheduleSettings() {
        scheduleSwitch.isChecked = prefs.getBoolean("schedule_enabled", false)
        startHour = prefs.getInt("start_hour", 18)
        startMinute = prefs.getInt("start_minute", 0)
        endHour = prefs.getInt("end_hour", 23)
        endMinute = prefs.getInt("end_minute", 0)
    }

    private fun updateUIState(enabled: Boolean) {
        startTimeButton.isEnabled = enabled
        endTimeButton.isEnabled = enabled
        findViewById<Button>(R.id.presetSleepButton).isEnabled = enabled
        findViewById<Button>(R.id.presetWorkButton).isEnabled = enabled
        findViewById<Button>(R.id.presetEveningButton).isEnabled = enabled
    }

    private fun showTimePickerDialog(isStartTime: Boolean) {
        val hour = if (isStartTime) startHour else endHour
        val minute = if (isStartTime) startMinute else endMinute
        
        TimePickerDialog(
            this,
            { _, selectedHour, selectedMinute ->
                if (isStartTime) {
                    startHour = selectedHour
                    startMinute = selectedMinute
                } else {
                    endHour = selectedHour
                    endMinute = selectedMinute
                }
                updateTimeDisplays()
            },
            hour,
            minute,
            false // 12-hour format
        ).show()
    }

    private fun setPresetTime(sHour: Int, sMinute: Int, eHour: Int, eMinute: Int) {
        startHour = sHour
        startMinute = sMinute
        endHour = eHour
        endMinute = eMinute
        updateTimeDisplays()
        
        Toast.makeText(this, "Preset applied! Tap Save to confirm", Toast.LENGTH_SHORT).show()
    }

    private fun updateTimeDisplays() {
        startTimeText.text = String.format("%02d:%02d %s", 
            if (startHour > 12) startHour - 12 else if (startHour == 0) 12 else startHour,
            startMinute,
            if (startHour >= 12) "PM" else "AM"
        )
        
        endTimeText.text = String.format("%02d:%02d %s",
            if (endHour > 12) endHour - 12 else if (endHour == 0) 12 else endHour,
            endMinute,
            if (endHour >= 12) "PM" else "AM"
        )
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                AlertDialog.Builder(this)
                    .setTitle("Permission Required")
                    .setMessage("To use scheduled alarms, please grant 'Alarms & reminders' permission in the next screen.")
                    .setPositiveButton("Open Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun saveSchedule() {
        val editor = prefs.edit()
        editor.putBoolean("schedule_enabled", scheduleSwitch.isChecked)
        editor.putInt("start_hour", startHour)
        editor.putInt("start_minute", startMinute)
        editor.putInt("end_hour", endHour)
        editor.putInt("end_minute", endMinute)
        editor.apply()
        
        if (scheduleSwitch.isChecked) {
            scheduleAlarms()
            Toast.makeText(this, "Schedule saved and activated!", Toast.LENGTH_SHORT).show()
        } else {
            cancelAlarms()
            Toast.makeText(this, "Schedule disabled", Toast.LENGTH_SHORT).show()
        }
        
        finish()
    }

    private fun scheduleAlarms() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // Schedule start alarm
        val startIntent = Intent(this, ScheduleReceiver::class.java).apply {
            action = "START_SERVICE"
        }
        val startPendingIntent = PendingIntent.getBroadcast(
            this, 100, startIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val startCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, startHour)
            set(Calendar.MINUTE, startMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            // If time has passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        
        // Schedule stop alarm
        val stopIntent = Intent(this, ScheduleReceiver::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 101, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val endCalendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, endHour)
            set(Calendar.MINUTE, endMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            
            // If time has passed today, schedule for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        
        // Set repeating alarms
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    startCalendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    startPendingIntent
                )
                
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    endCalendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    stopPendingIntent
                )
            }
        } else {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                startCalendar.timeInMilli
