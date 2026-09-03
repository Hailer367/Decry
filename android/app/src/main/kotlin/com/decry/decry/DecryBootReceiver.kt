package com.decry.decry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * DecryBootReceiver
 * 
 * Automatically starts the app and services after device reboot.
 * Ensures persistence across device restarts.
 */
class DecryBootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "DecryBootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i(TAG, "Received action: $action")

        if (action != null && (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_QUICKBOOT_POWERON ||
            "com.htc.intent.action.QUICKBOOT_POWERON" == action
        )) {
            try {
                // Start the background service
                val serviceIntent = Intent(context, DecryBackgroundService::class.java)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                
                Log.i(TAG, "Background service started after boot")
                
                // Also try to start accessibility service if enabled
                val accessibilityIntent = Intent(context, DecryAccessibilityService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(accessibilityIntent)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting services after boot: ${e.message}")
            }
        }
    }
}
