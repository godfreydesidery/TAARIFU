/// Tests the push deep-link parsing (US-5.1 tap-through): a REPORT_STATUS payload
/// resolves to a report target so the tap opens that timeline, tolerating the key
/// spellings the channel owners may use (push/SMS/in-app must converge — US-3.9).
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/features/notifications/data/push_service.dart';

void main() {
  group('NotificationDeepLink', () {
    test('REPORT_STATUS with a reportId points at a report timeline', () {
      final link = NotificationDeepLink.fromData({
        'type': 'REPORT_STATUS',
        'reportId': 'r-42',
      });
      expect(link.isReport, isTrue);
      expect(link.targetId, 'r-42');
    });

    test('tolerates targetId / payloadRef spellings', () {
      expect(
        NotificationDeepLink.fromData({
          'type': 'REPORT',
          'targetId': 'r-1',
        }).targetId,
        'r-1',
      );
      expect(
        NotificationDeepLink.fromData({
          'notificationType': 'CASE_EVENT',
          'payloadRef': 'r-2',
        }).isReport,
        isTrue,
      );
    });

    test('an announcement (or unknown) is not a report → inbox fallback', () {
      final link = NotificationDeepLink.fromData({
        'type': 'ANNOUNCEMENT',
        'targetId': 'a-1',
      });
      expect(link.isReport, isFalse);
    });

    test('a report type with no target id is not routable to a report', () {
      final link = NotificationDeepLink.fromData({'type': 'REPORT_STATUS'});
      expect(link.isReport, isFalse);
    });
  });

  test('UnavailablePushService is a safe no-op default', () async {
    const push = UnavailablePushService();
    expect(push.isAvailable, isFalse);
    await push.registerToken();
    await push.clearToken();
    expect(await push.onMessageOpened.isEmpty, isTrue);
  });
}
