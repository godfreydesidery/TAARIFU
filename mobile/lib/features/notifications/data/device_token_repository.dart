/// Repository for push device-token registration (PRD §13, EI-5, US-5.1;
/// `POST /notification-tokens`, `DELETE /notification-tokens/{token}`).
///
/// Responsibility: turn the FCM token lifecycle into [ApiClient] calls against
/// the real `DeviceTokenController`. Registration is the backend's idempotent
/// upsert (re-posting a known token re-binds + refreshes last-seen, never a
/// duplicate — DI4), so the [PushService] can call [register] freely on every
/// app start and token rotation. Unregister is the secure-logout step.
///
/// Secret handling (PRD §18): the FCM token is a sensitive routing credential —
/// it is sent in the body / path but **never logged** here, and the response
/// (`DeviceTokenDto`) intentionally never echoes it back.
library;

import '../../../core/network/api_client.dart';

/// Registers and unregisters the caller's push device token.
class DeviceTokenRepository {
  /// Creates the repository over an [ApiClient].
  DeviceTokenRepository({required ApiClient apiClient}) : _api = apiClient;

  final ApiClient _api;

  /// Registers (or idempotently refreshes) the caller's [token] for [platform].
  ///
  /// Maps to `POST /notification-tokens` `{token, platform}`. [platform] is a
  /// backend `DevicePlatform` name (`ANDROID`/`IOS`/`WEB`). Returns the public id
  /// of the registration receipt (the token value is never returned).
  Future<String> register({
    required String token,
    required String platform,
  }) async {
    final result = await _api.post<String>(
      '/notification-tokens',
      body: {'token': token, 'platform': platform},
      // A stable idempotency key per token keeps an at-launch + on-rotation
      // double-register from racing into two writes (the server also de-dupes
      // by token value — defence in depth).
      idempotencyKey: 'device-token:$token',
      parser: (data) => (data! as Map<String, dynamic>)['id'] as String,
    );
    return result.data;
  }

  /// Unregisters [token] on logout (idempotent soft-delete server-side).
  ///
  /// Maps to `DELETE /notification-tokens/{token}`. Unregistering an already-gone
  /// token is a no-op success, so this is safe to call on every sign-out.
  Future<void> unregister(String token) async {
    await _api.delete<void>(
      '/notification-tokens/${Uri.encodeComponent(token)}',
      parser: (_) {},
    );
  }
}
