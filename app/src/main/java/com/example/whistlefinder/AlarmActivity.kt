package com.example.whistlefinder

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*

class AlarmActivity : AppCompatActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var originalVolume: Int = 0
    private lateinit var timeText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarm)

        // Show on lock screen and turn on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        timeText = findViewById(R.id.timeText)
        val stopButton: Button = findViewById(R.id.stopAlarmButton)
        
        // Display current time
        val currentTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        timeText.text = currentTime
        
        stopButton.setOnClickListener {
            stopAlarm()
            finish()
        }

        startAlarm()
    }

    private fun startAlarm() {
        // Get vibration preference
        val prefs = getSharedPreferences("WhistleFinderPrefs", MODE_PRIVATE)
        val vibrationEnabled = prefs.getBoolean("vibration_enabled", true)

        // Start vibration if enabled
        if (vibrationEnabled) {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            // Strong vibration pattern
            val pattern = longArrayOf(0, 400, 200, 400, 200) // Vibrate-pause pattern
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, 0) // 0 = repeat
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }

        // Play alarm sound at max volume
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        // Save current volume
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        
        // Set to max volume
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                
                // Use default alarm sound
                try {
                    setDataSource(
                        this@AlarmActivity,
                        android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
                    )
                } catch (e: Exception) {
                    // Fallback to ringtone if alarm URI fails
                    setDataSource(
                        this@AlarmActivity,
                        android.provider.Settings.System.DEFAULT_RINGTONE_URI
                    )
                }
                
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Last resort fallback
            playFallbackSound()
        }
    }

    private fun playFallbackSound() {
        try {
            mediaPlayer = MediaPlayer.create(
                this,
                android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
            )?.apply {
                isLooping = true
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopAlarm() {
        // Stop vibration
        vibrator?.cancel()
        vibrator = null

        // Stop sound
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        mediaPlayer = null

        // Restore original volume
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAlarm()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent back button from dismissing alarm
        // User must press the stop button
    }
}
