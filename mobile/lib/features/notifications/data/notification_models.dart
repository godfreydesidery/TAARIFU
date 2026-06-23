/// Wire models for the notifications endpoints, mirroring the backend DTOs in
/// `com.taarifu.communications.api.dto` (NotificationController,
/// NotificationPreferenceController).
library;

/// A single in-app notification (backend `NotificationDto`).
///
/// Carries no inline PII — only the type, channel, delivery status, a deep-link
/// [payloadRef], and timestamps. The recipient is implicitly the caller.
class AppNotification {
  /// Creates a notification.
  const AppNotification({
    required this.id,
    required this.type,
    required this.channel,
    required this.status,
    this.payloadRef,
    this.createdAt,
    this.readAt,
  });

  /// Notification public id (UUID string) — the mark-read target.
  final String id;

  /// Event type name (e.g. REPORT_STATUS, ANNOUNCEMENT).
  final String type;

  /// Delivery channel name (PUSH/SMS/FEED/EMAIL).
  final String channel;

  /// Delivery status name (QUEUED/SENT/DELIVERED/READ/FAILED).
  final String status;

  /// Deep-link / source-content reference, or `null`.
  final String? payloadRef;

  /// Created instant (UTC), or `null`.
  final DateTime? createdAt;

  /// Read instant (UTC), or `null` if unread.
  final DateTime? readAt;

  /// Whether the recipient has read this notification.
  bool get isRead => readAt != null || status == 'READ';

  /// Parses a notification node.
  factory AppNotification.fromJson(Map<String, dynamic> json) =>
      AppNotification(
        id: json['id'] as String,
        type: json['type'] as String? ?? '',
        channel: json['channel'] as String? ?? '',
        status: json['status'] as String? ?? '',
        payloadRef: json['payloadRef'] as String?,
        createdAt: DateTime.tryParse(json['createdAt'] as String? ?? ''),
        readAt: DateTime.tryParse(json['readAt'] as String? ?? ''),
      );
}

/// A notification preference (backend `NotificationPreferenceDto`).
///
/// Identity is the `(type, channel)` pair; the rest are mutable settings. The
/// backend rejects disabling always-on types (SYSTEM/MODERATION_OUTCOME).
class NotificationPreference {
  /// Creates a preference.
  const NotificationPreference({
    required this.id,
    required this.type,
    required this.channel,
    required this.enabled,
    this.language,
  });

  /// Preference public id (UUID string).
  final String id;

  /// Governed notification type name.
  final String type;

  /// Governed channel name.
  final String channel;

  /// Whether opted in.
  final bool enabled;

  /// Preferred language tag (`sw`/`en`), or `null`.
  final String? language;

  /// Parses a preference node.
  factory NotificationPreference.fromJson(Map<String, dynamic> json) =>
      NotificationPreference(
        id: json['id'] as String,
        type: json['type'] as String? ?? '',
        channel: json['channel'] as String? ?? '',
        enabled: json['enabled'] == true,
        language: json['language'] as String?,
      );
}
