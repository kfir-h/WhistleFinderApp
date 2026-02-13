package com.example.whistlefinder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ScheduleReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        
        when (action) {
            "START_SERVICE" -> {
                startWhistleService(context)
            }
            "STOP_SERVICE" -> {
                stopWhistleService(context)
            }
            Intent.ACTION_BOOT_COMPLETED -> {
                // Re-schedule alarms after device reboot
                rescheduleAlarms(context)
            }
        }
    }
    
    private fun startWhistleService(context: Context) {
        val serviceIntent = Intent(context, WhistleDetectionService::class.java)
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun stopWhistleService(context: Context) {
        val serviceIntent = Intent(context, WhistleDetectionService::class.java)
        context.stopService(serviceIntent)
    }
    
    private fun rescheduleAlarms(context: Context) {
        val prefs = context.getSharedPreferences("WhistleFinderPrefs", Context.MODE_PRIVATE)
        val scheduleEnabled = prefs.getBoolean("schedule_enabled", false)
        
        if (scheduleEnabled) {
            // Trigger reschedule by sending broadcast to MainActivity
            val rescheduleIntent = Intent("com.example.whistlefinder.RESCHEDULE_ALARMS")
            context.sendBroadcast(rescheduleIntent)
        }
    }
}
