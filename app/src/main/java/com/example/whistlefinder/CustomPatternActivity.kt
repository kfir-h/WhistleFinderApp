package com.example.whistlefinder

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class CustomPatternActivity : AppCompatActivity() {

    private lateinit var patternTypeGroup: RadioGroup
    private lateinit var recordButton: Button
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var clearButton: Button
    private lateinit var statusText: TextView
    private lateinit var instructionText: TextView
    private lateinit var patternNameEdit: EditText
    private lateinit var prefs: SharedPreferences
    
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var recordedPattern: FloatArray? = null
    
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val RECORDING_DURATION_MS = 3000 // 3 seconds
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_pattern)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Custom Pattern"
        
        prefs = getSharedPreferences("WhistleFinderPrefs", MODE_PRIVATE)
        
        initializeViews()
        setupListeners()
        loadExistingPattern()
    }

    private fun initializeViews() {
        patternTypeGroup = findViewById(R.id.patternTypeGroup)
        recordButton = findViewById(R.id.recordButton)
        saveButton = findViewById(R.id.saveButton)
        testButton = findViewById(R.id.testButton)
        clearButton = findViewById(R.id.clearButton)
        statusText = findViewById(R.id.statusText)
        instructionText = findViewById(R.id.instructionText)
        patternNameEdit = findViewById(R.id.patternNameEdit)
        
        saveButton.isEnabled = false
        testButton.isEnabled = false
    }

    private fun setupListeners() {
        patternTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            updateInstructions(checkedId)
        }
        
        recordButton.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
        
        saveButton.setOnClickListener {
            savePattern()
        }
        
        testButton.setOnClickListener {
            testPattern()
        }
        
        clearButton.setOnClickListener {
            clearPattern()
        }
    }

    private fun updateInstructions(checkedId: Int) {
        when (checkedId) {
            R.id.radioWhistle -> {
                instructionText.text = """
                    🎵 Custom Whistle Pattern
                    
                    1. Think of a unique whistle pattern
                    2. Press RECORD and whistle your pattern
                    3. Recording will last 3 seconds
                    4. Repeat the same pattern 3 times for accuracy
                    5. Give it a name and SAVE
                    
                    Examples:
                    • Two short, one long whistle
                    • Three quick whistles
                    • High-low-high pattern
                    • Your favorite tune snippet
                """.trimIndent()
            }
            R.id.radioVoice -> {
                instructionText.text = """
                    🗣️ Voice Command / Code Name
                    
                    1. Choose a unique phrase or name
                    2. Press RECORD and speak clearly
                    3. Say your phrase 2-3 times
                    4. Keep it short (2-5 words)
                    5. Name it and SAVE
                    
                    Examples:
                    • "Hey [YourName] phone"
                    • "Marco" (like Marco Polo!)
                    • "Where are you buddy"
                    • "Ring ring phone"
                    • Your pet's name
                """.trimIndent()
            }
            R.id.radioBoth -> {
                instructionText.text = """
                    🎵🗣️ Dual Mode
                    
                    App will respond to EITHER:
                    • Your custom whistle pattern, OR
                    • Your voice command
                    
                    Record both:
                    1. Select "Whistle" and record pattern
                    2. Select "Voice" and record command
                    3. Both will be active
                    
                    Most secure option!
                """.trimIndent()
            }
        }
    }

    private fun startRecording() {
        if (!checkPermission()) {
            requestPermissions()
            return
        }
        
        isRecording = true
        recordButton.text = "⏹ STOP RECORDING"
        recordButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        statusText.text = "🔴 Recording... Make your sound NOW!"
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 4
            )
            
            audioRecord?.startRecording()
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudioPattern(bufferSize)
            }
            
            // Auto-stop after duration
            CoroutineScope(Dispatchers.Main).launch {
                delay(RECORDING_DURATION_MS.toLong())
                if (isRecording) {
                    stopRecording()
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_SHORT).show()
            isRecording = false
        }
    }

    private fun recordAudioPattern(bufferSize: Int) {
        val audioData = mutableListOf<Short>()
        val buffer = ShortArray(bufferSize)
        
        val maxSamples = (SAMPLE_RATE * RECORDING_DURATION_MS) / 1000
        var samplesRead = 0
        
        while (isRecording && samplesRead < maxSamples) {
            val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
            if (readSize > 0) {
                for (i in 0 until readSize) {
                    audioData.add(buffer[i])
                    samplesRead++
                    if (samplesRead >= maxSamples) break
                }
            }
        }
        
        // Convert to feature vector (audio fingerprint)
        recordedPattern = extractAudioFeatures(audioData.toTypedArray())
        
        runOnUiThread {
            if (recordedPattern != null) {
                statusText.text = "✅ Pattern recorded successfully!"
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                saveButton.isEnabled = true
                testButton.isEnabled = true
            }
        }
    }

    private fun extractAudioFeatures(audioData: Array<Short>): FloatArray {
        // Extract key features for pattern matching
        val features = mutableListOf<Float>()
        
        // 1. Energy (RMS)
        var energy = 0.0
        for (sample in audioData) {
            energy += sample * sample
        }
        val rms = sqrt(energy / audioData.size)
        features.add(rms.toFloat())
        
        // 2. Zero-crossing rate
        var zeroCrossings = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0 && audioData[i-1] < 0) || 
                (audioData[i] < 0 && audioData[i-1] >= 0)) {
                zeroCrossings++
            }
        }
        features.add(zeroCrossings.toFloat())
        
        // 3. Spectral features - divide into frequency bands
        val chunkSize = audioData.size / 10
        for (i in 0 until 10) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, audioData.size)
            
            var chunkEnergy = 0.0
            for (j in start until end) {
                chunkEnergy += audioData[j] * audioData[j]
            }
            features.add(sqrt(chunkEnergy / chunkSize).toFloat())
        }
        
        // 4. Peak detection - find loudest points
        val peaks = findPeaks(audioData)
        features.add(peaks.size.toFloat())
        
        // Add timing between peaks
        if (peaks.size > 1) {
            for (i in 1 until minOf(peaks.size, 5)) {
                features.add((peaks[i] - peaks[i-1]).toFloat())
            }
        }
        
        return features.toFloatArray()
    }

    private fun findPeaks(audioData: Array<Short>): List<Int> {
        val peaks = mutableListOf<Int>()
        val threshold = audioData.map { abs(it.toInt()) }.average() * 2
        
        for (i in 1 until audioData.size - 1) {
            val current = abs(audioData[i].toInt())
            val prev = abs(audioData[i-1].toInt())
            val next = abs(audioData[i+1].toInt())
            
            if (current > threshold && current > prev && current > next) {
                peaks.add(i)
            }
        }
        
        return peaks
    }

    private fun stopRecording() {
        isRecording = false
        recordButton.text = "🔴 START RECORDING"
        recordButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioRecord = null
    }

    private fun savePattern() {
        val patternName = patternNameEdit.text.toString().trim()
        
        if (patternName.isEmpty()) {
            Toast.makeText(this, "Please enter a name for your pattern", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (recordedPattern == null) {
            Toast.makeText(this, "No pattern recorded yet", Toast.LENGTH_SHORT).show()
            return
        }
        
        val selectedType = when (patternTypeGroup.checkedRadioButtonId) {
            R.id.radioWhistle -> "whistle"
            R.id.radioVoice -> "voice"
            R.id.radioBoth -> "both"
            else -> "whistle"
        }
        
        // Save pattern to file
        val patternFile = File(filesDir, "custom_pattern_$selectedType.dat")
        try {
            FileOutputStream(patternFile).use { fos ->
                val data = recordedPattern!!.joinToString(",")
                fos.write(data.toByteArray())
            }
            
            // Save metadata
            prefs.edit().apply {
                putBoolean("custom_pattern_enabled", true)
                putString("custom_pattern_type", selectedType)
                putString("custom_pattern_name", patternName
