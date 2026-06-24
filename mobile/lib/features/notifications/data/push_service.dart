/// The push-notification (FCM) seam: token lifecycle + tap-through deep links
/// into the app (US-5.1, PRD §13).
///
/// WHY a seam, not `firebase_messaging` yet: Firebase pulls a sizeable native
/// dependency and a Google-services config that grows the APK and the build for a
/// citizen on a tiny bundle (PRD §15). We model the *contract* — token register,
/// foreground/background/terminated message handling, and the **deep-link payload
/// shape** — so the routing UX (a notification tap opens the right report
/// timeline) is real and testable now; wiring `firebase_messaging` later only
/// replaces the binding, not the routing code (clean boundaries, CLAUDE.md §3).
///
/// SMS-fallback awareness (US-5.1): the backend falls back to SMS when there is
/// no live push token. The app surfaces that on the inbox; the token lifecycle
/// here is what keeps a *live* token registered so push (the cheaper channel) is
/// preferred when possible.
library;

/// A deep link parsed from a notification payload, telling the app where to go.
///
/// The backend stamps `type` + a target id on the data payload (mirroring the
/// in-app [AppNotification.payloadRef]); the client maps it to a screen. Unknown
/// types degrade gracefully to the inbox rather than crashing on a tap.
class NotificationDeepLink {
  /// Creates a deep link.
  const NotificationDeepLink({required this.type, this.targetId});

  /// The notification type (e.g. `REPORT_STATUS`, `ANNOUNCEMENT`).
  final String type;

  /// The target entity id (e.g. a report id), or `null`.
  final String? targetId;

  /// Whether this link points at a specific report timeline.
  bool get isReport =>
      (type == 'REPORT_STATUS' || type == 'REPORT' || type == 'CASE_EVENT') &&
      (targetId != null && targetId!.isNotEmpty);

  /// Parses a deep link from an FCM data payload (string→string map).
  ///
  /// Tolerates the common key spellings backends use (`type`/`notificationType`,
  /// `targetId`/`reportId`/`payloadRef`) so the contract is forgiving across the
  /// channel owners (push + SMS + in-app must converge — US-3.9 parity).
  factory NotificationDeepLink.fromData(Map<String, dynamic> data) {
    String? str(String key) => data[key]?.toString();
    return NotificationDeepLink(
      type: str('type') ?? str('notificationType') ?? '',
      targetId: str('targetId') ?? str('reportId') ?? str('payloadRef'),
    );
  }
}

/// Manages the FCM token lifecycle and surfaces notification taps as deep links.
abstract interface class PushService {
  /// Whether real push is available on this build (drives token/UI behaviour).
  bool get isAvailable;

  /// Registers/refreshes the device token with the backend so this device can
  /// receive push (and so SMS-fallback is only used when no token is live).
  Future<void> registerToken();

  /// Clears the device token on sign-out (so a shared device stops receiving the
  /// previous citizen's push — security/PDPA, PRD §18).
  Future<void> clearToken();

  /// A stream of notification taps, each resolved to a [NotificationDeepLink]
  /// the app routes on (e.g. open a report's timeline).
  Stream<NotificationDeepLink> get onMessageOpened;
}

/// The default, dependency-free binding: push is not wired yet.
///
/// Keeps the contract honest — the app builds and runs, SMS-fallback copy is
/// shown, and token calls are no-ops until `firebase_messaging` lands.
class UnavailablePushService implements PushService {
  /// Creates the unavailable service.
  const UnavailablePushService();

  @override
  bool get isAvailable => false;

  @override
  Future<void> registerToken() async {}

  @override
  Future<void> clearToken() async {}

  @override
  Stream<NotificationDeepLink> get onMessageOpened =>
      const Stream<NotificationDeepLink>.empty();
}
