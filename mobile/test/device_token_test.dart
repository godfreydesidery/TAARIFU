/// Tests for push device-token registration (PRD §13, EI-5, US-5.1).
///
/// Covers the [DeviceTokenRepository] register/unregister mapping to
/// `/notification-tokens` (idempotency key per token; token never echoed), and
/// the [UnavailablePushService] seam: with no FCM token available it correctly
/// skips registration (so SMS fallback applies), and with a token provider it
/// registers/unregisters through the repository — the lifecycle that goes live
/// the moment `firebase_messaging` supplies a real token.
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/features/notifications/data/device_token_repository.dart';
import 'package:taarifu_citizen/features/notifications/data/push_service.dart';

/// A fake [ApiClient] recording POST/DELETE calls for the token endpoints.
class _FakeApiClient implements ApiClient {
  final List<({String path, Object? body, String? key})> posts = [];
  final List<String> deletes = [];

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async {
    posts.add((path: path, body: body, key: idempotencyKey));
    return ApiResult<T>(data: parser({'id': 'dt-1'}));
  }

  @override
  Future<ApiResult<T>> delete<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async {
    deletes.add(path);
    return ApiResult<T>(data: parser(null));
  }

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();

  @override
  Future<ApiResult<T>> put<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();
}

void main() {
  group('DeviceTokenRepository', () {
    late _FakeApiClient api;
    late DeviceTokenRepository repo;

    setUp(() {
      api = _FakeApiClient();
      repo = DeviceTokenRepository(apiClient: api);
    });

    test('register posts {token, platform} with a per-token idempotency key',
        () async {
      final id = await repo.register(token: 'fcm-abc', platform: 'ANDROID');
      expect(id, 'dt-1');
      final post = api.posts.single;
      expect(post.path, '/notification-tokens');
      expect((post.body! as Map)['token'], 'fcm-abc');
      expect((post.body! as Map)['platform'], 'ANDROID');
      expect(post.key, 'device-token:fcm-abc');
    });

    test('unregister deletes the url-encoded token path', () async {
      await repo.unregister('fcm/abc');
      expect(api.deletes.single, '/notification-tokens/fcm%2Fabc');
    });
  });

  group('UnavailablePushService', () {
    test('with no FCM token available, registration is skipped (SMS fallback)',
        () async {
      final api = _FakeApiClient();
      final service = UnavailablePushService(
        tokenRepository: DeviceTokenRepository(apiClient: api),
        // No token provider → no token → nothing to register.
      );
      expect(service.isAvailable, isFalse);
      await service.registerToken();
      await service.clearToken();
      expect(api.posts, isEmpty);
      expect(api.deletes, isEmpty);
    });

    test('with a token provider, it registers then unregisters via the backend',
        () async {
      final api = _FakeApiClient();
      final service = UnavailablePushService(
        tokenRepository: DeviceTokenRepository(apiClient: api),
        tokenProvider: () async => 'fcm-live',
      );
      await service.registerToken();
      await service.clearToken();
      expect(api.posts.single.path, '/notification-tokens');
      expect(api.deletes.single, '/notification-tokens/fcm-live');
    });

    test('a register failure is swallowed (push must never block startup)',
        () async {
      // A repository over a client whose POST throws → registerToken must not.
      final service = UnavailablePushService(
        tokenRepository: DeviceTokenRepository(apiClient: _ThrowingApiClient()),
        tokenProvider: () async => 'fcm-live',
      );
      await expectLater(service.registerToken(), completes);
    });
  });
}

/// An [ApiClient] whose POST always throws, to prove the push seam degrades.
class _ThrowingApiClient implements ApiClient {
  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async => throw StateError('boom');

  @override
  dynamic noSuchMethod(Invocation invocation) => throw UnimplementedError();
}
