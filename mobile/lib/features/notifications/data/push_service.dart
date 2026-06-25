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

import 'device_token_repository.dart';

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

/// The default, dependency-free binding: real push (FCM) is not wired yet.
///
/// WHY this still talks to the backend token endpoint: the token-registration
/// **scaffolding** is real and testable now — [registerToken] obtains a token
/// from [tokenProvider] (the only seam `firebase_messaging` later fills) and, if
/// one is available AND a [DeviceTokenRepository] was supplied, registers it via
/// `POST /notification-tokens`; [clearToken] unregisters it on logout. With no
/// `firebase_messaging` in the budget yet (PRD §15), [tokenProvider] returns
/// `null`, so registration is correctly skipped (SMS-fallback copy is shown
/// instead) — but the entire lifecycle wiring is in place and exercised by the
/// repository's tests. Swapping in a real FCM token provider is the only change
/// needed to go live (clean boundaries, CLAUDE.md §3).
///
/// Degrades gracefully: any failure to register/unregister is swallowed — push
/// is a non-critical channel and must never block app start or sign-out (the
/// backend's SMS fallback still reaches the citizen — US-5.1).
class UnavailablePushService implements PushService {
  /// Creates the service.
  ///
  /// [tokenRepository] performs the backend register/unregister (omit it to keep
  /// the pure no-op behaviour, e.g. in tests). [platform] is the backend
  /// `DevicePlatform` name for this device (defaults to `ANDROID`, the citizen
  /// app's primary target). [tokenProvider] yields the FCM token when push is
  /// wired; the default returns `null` (no token available yet).
  const UnavailablePushService({
    DeviceTokenRepository? tokenRepository,
    String platform = 'ANDROID',
    Future<String?> Function()? tokenProvider,
  }) : _tokenRepository = tokenRepository,
       _platform = platform,
       _tokenProvider = tokenProvider;

  final DeviceTokenRepository? _tokenRepository;
  final String _platform;
  final Future<String?> Function()? _tokenProvider;

  @override
  bool get isAvailable => false;

  @override
  Future<void> registerToken() async {
    final repo = _tokenRepository;
    if (repo == null) return;
    try {
      final token = await (_tokenProvider?.call() ?? Future<String?>.value());
      if (token == null || token.isEmpty) return;
      await repo.register(token: token, platform: _platform);
    } on Object {
      // Push is best-effort; never let a failed register block startup.
    }
  }

  @override
  Future<void> clearToken() async {
    final repo = _tokenRepository;
    if (repo == null) return;
    try {
      final token = await (_tokenProvider?.call() ?? Future<String?>.value());
      if (token == null || token.isEmpty) return;
      await repo.unregister(token);
    } on Object {
      // Best-effort: a failed unregister must not block secure logout.
    }
  }

  @override
  Stream<NotificationDeepLink> get onMessageOpened =>
      const Stream<NotificationDeepLink>.empty();
}
