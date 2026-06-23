/// Tests the offline-first submit invariant on [ReportRepository]
/// (PRD §15, §17, UC-D03): airplane-mode file → queued draft → reconnect →
/// sync → exactly one server call PER draft, replayed with the SAME idempotency
/// key (so the backend de-dupes — never a duplicate ticket).
library;

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/network/api_exception.dart';
import 'package:taarifu_citizen/core/storage/outbox_store.dart';
import 'package:taarifu_citizen/features/reporting/data/report_repository.dart';
import 'package:taarifu_citizen/features/reporting/data/reporting_models.dart';

/// A controllable fake [ApiClient]: it can be put "offline" (every POST throws
/// [OfflineException]) or "online" (every POST succeeds), and it records each
/// POST's path + idempotency key so the test can assert no duplicate submit.
class _FakeApiClient implements ApiClient {
  bool offline = false;

  final List<({String path, String? key})> posts = [];

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async {
    posts.add((path: path, key: idempotencyKey));
    if (offline) {
      throw const OfflineException();
    }
    // Return a minimal report payload the parser can decode.
    final data = <String, dynamic>{
      'id': 'r-1',
      'code': 'TAR-2026-000001',
      'title': 'x',
      'status': 'NEW',
    };
    return ApiResult<T>(data: parser(data));
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

const _draft = ReportDraft(
  categoryId: 'c-1',
  title: 'Maji yamekatika',
  description: 'Hakuna maji kwa siku tatu',
  wardId: 'w-1',
);

void main() {
  group('ReportRepository offline-first submit', () {
    late _FakeApiClient api;
    late InMemoryOutboxStore outbox;
    late ReportRepository repo;

    setUp(() {
      api = _FakeApiClient();
      outbox = InMemoryOutboxStore();
      repo = ReportRepository(apiClient: api, outbox: outbox);
    });

    test('online file returns a filed outcome and does NOT queue', () async {
      final outcome = await repo.fileReport(_draft);
      expect(outcome.queued, isFalse);
      expect(outcome.report?.code, 'TAR-2026-000001');
      expect(await repo.pendingDrafts(), isEmpty);
    });

    test('offline file queues a draft instead of failing', () async {
      api.offline = true;
      final outcome = await repo.fileReport(_draft);
      expect(outcome.queued, isTrue);
      final pending = await repo.pendingDrafts();
      expect(pending, hasLength(1));
      expect(pending.first.key, isNotEmpty);
    });

    test(
      'offline → queue → reconnect → sync sends each draft exactly once, '
      'with its original idempotency key (no duplicate ticket)',
      () async {
        // Airplane mode: two reports drafted offline.
        api.offline = true;
        await repo.fileReport(_draft);
        await repo.fileReport(_draft);
        expect(api.posts, hasLength(2)); // both attempted, both failed→queued
        final queuedKeys = (await repo.pendingDrafts())
            .map((e) => e.key)
            .toList();
        expect(queuedKeys, hasLength(2));

        // A bar appears: reconnect and flush.
        api.posts.clear();
        api.offline = false;
        final synced = await repo.syncPending();

        expect(synced, 2);
        // Exactly one POST per draft on sync — no duplicate submits.
        expect(api.posts, hasLength(2));
        // Replayed with the SAME keys minted at draft time (de-dupe contract).
        expect(api.posts.map((p) => p.key).toSet(), queuedKeys.toSet());
        // Queue is drained after a successful sync.
        expect(await repo.pendingDrafts(), isEmpty);
      },
    );

    test('sync while still offline leaves the draft pending (safe retry)',
        () async {
      api.offline = true;
      await repo.fileReport(_draft);
      final synced = await repo.syncPending();
      expect(synced, 0);
      expect(await repo.pendingDrafts(), hasLength(1));
    });
  });
}
