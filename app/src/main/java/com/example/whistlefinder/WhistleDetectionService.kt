package com.example.whistlefinder

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class WhistleDetectionService : Service() {

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var prefs: SharedPreferences

    companion object {
        const val CHANNEL_ID = "WhistleDetectionChannel"
        const val NOTIFICATION_ID = 1
        var isServiceRunning = false

        // Audio parameters
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        
        // Whistle detection parameters
        private const val WHISTLE_FREQ_MIN = 800.0  // Hz
        private const val WHISTLE_FREQ_MAX = 3500.0 // Hz
        private const val BASE_THRESHOLD = 5000.0 // Base amplitude threshold
        
        // Dynamic sensitivity
        private var sensitivityMultiplier = 1.0
        
        fun updateSensitivity(sensitivity: Int) {
            // Convert 0-100 to multiplier (50 = 1.0, 0 = 2.0, 100 = 0.5)
            sensitivityMultiplier = 2.0 - (sensitivity / 50.0)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        prefs = getSharedPreferences("WhistleFinderPrefs", Context.MODE_PRIVATE)
        
        // Load sensitivity
        val sensitivity = prefs.getInt("sensitivity", 50)
        updateSensitivity(sensitivity)
        
        // Acquire wake lock to keep CPU awake
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "WhistleFinder::WakeLock"
        )
        wakeLock.acquire(10*60*60*1000L /*10 hours max*/)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        isServiceRunning = true
        startListening()
        
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        stopListening()
        
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Whistle Detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for whistles in background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // Add stop action
        val stopIntent = Intent(this, WhistleDetectionService::class.java)
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎤 Whistle Finder Active")
            .setContentText("Listening for whistles... Tap to open app")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startListening() {
        if (isRecording) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        ) * 4

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                processAudio(bufferSize)
            }
            recordingThread?.start()

        } catch (e: SecurityException) {
            e.printStackTrace()
            stopSelf()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
            stopSelf()
        }
    }

    private fun stopListening() {
        isRecording = false
        
        recordingThread?.join(1000)
        recordingThread = null
        
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    private fun processAudio(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize)
        
        var consecutiveDetections = 0
        val requiredConsecutiveDetections = 3

        while (isRecording) {
            try {
                val readSize = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
                
                if (readSize > 0) {
                    // Simple whistle detection based on amplitude and frequency
                    if (detectWhistle(audioBuffer, readSize)) {
                        consecutiveDetections++
                        
                        if (consecutiveDetections >= requiredConsecutiveDetections) {
                            onWhistleDetected()
                            consecutiveDetections = 0
                            
                            // Pause detection briefly to avoid multiple triggers
                            Thread.sleep(3000)
                        }
                    } else {
                        consecutiveDetections = 0
                    }
                }
                
                // Small sleep to reduce CPU usage
                Thread.sleep(50)
                
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun detectWhistle(buffer: ShortArray, size: Int): Boolean {
        // Check if custom pattern is enabled
        val customEnabled = prefs.getBoolean("custom_pattern_enabled", false)
        
        if (customEnabled) {
            return detectCustomPattern(buffer, size)
        }
        
        // Default whistle detection
        // Calculate RMS (Root Mean Square) amplitude
        var sum = 0.0
        for (i in 0 until size) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / size)

        // Apply dynamic threshold based on sensitivity
        val threshold = BASE_THRESHOLD * sensitivityMultiplier
        
        // Check if amplitude exceeds threshold
        if (rms < threshold) {
            return false
        }

        // Simple frequency detection using zero-crossing rate
        var zeroCrossings = 0
        for (i in 1 until size) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                zeroCrossings++
            }
        }

        // Estimate frequency from zero crossings
        val frequency = (zeroCrossings * SAMPLE_RATE) / (2.0 * size)

        // Check if frequency is in whistle range
        return frequency in WHISTLE_FREQ_MIN..WHISTLE_FREQ_MAX
    }
    
    private fun detectCustomPattern(buffer: ShortArray, size: Int): Boolean {
        val patternType = prefs.getString("custom_pattern_type", "whistle")
        val threshold = prefs.getFloat("pattern_threshold", 0.7f)
        
        // Extract features from current audio
        val currentFeatures = extractAudioFeatures(buffer, size)
        
        // Load saved pattern
        val savedPattern = loadCustomPattern(patternType ?: "whistle")
        if (savedPattern == null) {
            // Fall back to default detection
            return false
        }
        
        // Calculate similarity
        val similarity = calculateSimilarity(currentFeatures, savedPattern)
        
        return similarity >= threshold
    }
    
    private fun extractAudioFeatures(buffer: ShortArray, size: Int): FloatArray {
        val features = mutableListOf<Float>()
        
        // RMS Energy
        var energy = 0.0
        for (i in 0 until size) {
            energy += buffer[i] * buffer[i]
        }
        features.add(sqrt(energy / size).toFloat())
        
        // Zero-crossing rate
        var zeroCrossings = 0
        for (i in 1 until size) {
            if ((buffer[i] >= 0 && buffer[i-1] < 0) || (buffer[i] < 0 && buffer[i-1] >= 0)) {
                zeroCrossings++
            }
        }
        features.add(zeroCrossings.toFloat())
        
        // Spectral bands
        val chunkSize = size / 10
        for (i in 0 until 10) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, size)
            
            var chunkEnergy = 0.0
            for (j in start until end) {
                chunkEnergy += buffer[j] * buffer[j]
            }
            features.add(sqrt(chunkEnergy / chunkSize).toFloat())
        }
        
        return features.toFloatArray()
    }
    
    private fun loadCustomPattern(type: String): FloatArray? {
        return try {
            val patternFile = java.io.File(filesDir, "custom_pattern_$type.dat")
            if (!patternFile.exists()) return null
            
            val data = patternFile.readText()
            data.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    private fun calculateSimilarity(features1: FloatArray, features2: FloatArray): Float {
        if (features1.size != features2.size) return 0f
        
        // Normalize features
        val norm1 = normalizeFeatures(features1)
        val norm2 = normalizeFeatures(features2)
        
        // Calculate cosine similarity
        var dotProduct = 0.0
        var magnitude1 = 0.0
        var magnitude2 = 0.0
        
        for (i in norm1.indices) {
            dotProduct += norm1[i] * norm2[i]
            magnitude1 += norm1[i] * norm1[i]
            magnitude2 += norm2[i] * norm2[i]
        }
        
        val similarity = dotProduct / (sqrt(magnitude1) * sqrt(magnitude2))
        return similarity.toFloat()
    }
    
    private fun normalizeFeatures(features: FloatArray): FloatArray {
        val max = features.maxOrNull() ?: 1f
        val min = features.minOrNull() ?: 0f
        val range = max - min
        
        return if (range > 0) {
            features.map { (it - min) / range }.toFloatArray()
        } else {
            features
        }
    }

    private fun onWhistleDetected() {
        // Start the alarm activity
        val intent = Intent(this, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }
}
