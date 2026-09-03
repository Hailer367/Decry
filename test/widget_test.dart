import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:decry/main.dart';

void main() {
  testWidgets('renders the minimal Decry shell', (WidgetTester tester) async {
    const channel = MethodChannel('com.decry.permissions');
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(channel, (
      call,
    ) async {
      switch (call.method) {
        case 'requestRuntimePermissions':
          return <String, bool>{'granted': true};
        case 'getSpecialAccessStatus':
          return <String, bool>{
            'accessibilityEnabled': false,
            'notificationPolicyGranted': false,
          };
        default:
          return null;
      }
    });

    await tester.pumpWidget(const DecryApp());
    await tester.pump();
    if (find.text('Continue').evaluate().isNotEmpty) {
      await tester.tap(find.text('Continue'));
      await tester.pump();
    }
    await tester.pump();

    expect(find.text('Decry'), findsOneWidget);
    expect(find.byType(DecryApp), findsOneWidget);
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      channel,
      null,
    );
  });
}
