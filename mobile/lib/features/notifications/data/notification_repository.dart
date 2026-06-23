/// Repository for the citizen's notification inbox and preferences
/// (PRD §13, Epic M5, UC-G08/G09).
///
/// Responsibility: turn the notification use-cases into [ApiClient] calls against
/// the real `NotificationController` and `NotificationPreferenceController`. The
/// inbox is read-through cached so a citizen sees their last-known notifications
/// offline (PRD §15). Both surfaces are strictly the caller's own (PRD §18).
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'notification_models.dart';

/// Reads notifications + preferences and performs mark-read / upsert.
class NotificationRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  NotificationRepository({
    required ApiClient apiClient,
    required JsonCache cache,
  }) : _api = apiClient,
       _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  static const String _inboxKey = 'notifications.mine.page0';

  /// Lists the caller's notifications, newest first (`GET /notifications/mine`).
  ///
  /// Read-through cached so the inbox renders offline.
  Future<List<AppNotification>> listMine() async {
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/notifications/mine',
        query: {'page': 0, 'size': 30},
        parser: _asMapList,
      );
      await _cache.write(_inboxKey, result.data);
      return result.data
          .map(AppNotification.fromJson)
          .toList(growable: false);
    } on Object {
      final cached = await _cache.read(_inboxKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(AppNotification.fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  /// Marks one notification read (`POST /notifications/{id}/read`).
  Future<AppNotification> markRead(String notificationId) async {
    final result = await _api.post<AppNotification>(
      '/notifications/$notificationId/read',
      parser: (data) =>
          AppNotification.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  /// Lists the caller's notification preferences (`GET /notification-preferences/mine`).
  ///
  /// An empty list means defaults apply (the backend has no rows yet).
  Future<List<NotificationPreference>> listPreferences() async {
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/notification-preferences/mine',
      parser: _asMapList,
    );
    return result.data
        .map(NotificationPreference.fromJson)
        .toList(growable: false);
  }

  /// Upserts a single preference (`PUT /notification-preferences`).
  ///
  /// Keyed by `(type, channel)`. The backend rejects disabling always-on types.
  Future<NotificationPreference> upsertPreference({
    required String type,
    required String channel,
    required bool enabled,
    String? language,
  }) async {
    final result = await _api.put<NotificationPreference>(
      '/notification-preferences',
      body: {
        'type': type,
        'channel': channel,
        'enabled': enabled,
        'language': ?language,
      },
      parser: (data) =>
          NotificationPreference.fromJson(data! as Map<String, dynamic>),
    );
    return result.data;
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
