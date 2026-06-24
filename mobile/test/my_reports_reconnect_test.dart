/// Tests retry-on-reconnect on [MyReportsCubit] (PRD §15, §17, UC-D03): a draft
/// queued while offline is auto-flushed when connectivity returns, exactly once
/// per draft (replayed with its idempotency key → no duplicate ticket), and the
/// cubit surfaces how many were sent for the "synced N" feedback.
library;

import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/network/api_exception.dart';
import 'package:taarifu_citizen/core/network/connectivity_service.dart';
import 'package:taarifu_citizen/core/storage/outbox_store.dart';
import 'package:taarifu_citizen/features/reporting/bloc/my_reports_cubit.dart';
import 'package:taarifu_citizen/features/reporting/data/report_repository.dart';
import 'package:taarifu_citizen/features/reporting/data/reporting_models.dart';

/// A fake [ApiClient] toggled between offline (POSTs throw) and online, recording
/// each POST's idempotency key so the test can assert no duplicate submit.
class _FakeApiClient implements ApiClient {
  bool offline = false;
  final List<String?> postKeys = [];

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async {
    postKeys.add(idempotencyKey);
    if (offline) throw const OfflineException();
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
  }) async => ApiResult<T>(data: parser(const <Map<String, dynamic>>[]));

  @override
  Future<ApiResult<T>> put<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();
}

/// A connectivity service whose online/offline stream the test drives by hand.
class _FakeConnectivity extends ConnectivityService {
  final StreamController<bool> controller = StreamController<bool>.broadcast();

  @override
  Stream<bool> get onlineChanges => controller.stream;
}

const _draft = ReportDraft(
  categoryId: 'c-1',
  title: 'Maji yamekatika',
  description: 'Hakuna maji',
  wardId: 'w-1',
);

void main() {
  test(
    'a reconnect event auto-flushes queued drafts exactly once (no duplicates)',
    () async {
      final api = _FakeApiClient();
      final outbox = InMemoryOutboxStore();
      final repo = ReportRepository(apiClient: api, outbox: outbox);
      final conn = _FakeConnectivity();

      // Queue two drafts while offline.
      api.offline = true;
      await repo.fileReport(_draft);
      await repo.fileReport(_draft);
      final queuedKeys = (await repo.pendingDrafts())
          .map((e) => e.key)
          .toSet();
      expect(queuedKeys, hasLength(2));

      final cubit = MyReportsCubit(repository: repo, connectivity: conn);
      // Record every emitted state so we can assert the transient "synced N"
      // feedback fired (load() then clears it, by design, so it won't re-show).
      final seenSyncedCounts = <int>[];
      final sub = cubit.stream.listen(
        (s) => seenSyncedCounts.add(s.justSyncedCount),
      );
      await cubit.load();
      expect(cubit.state.drafts, hasLength(2));

      // A bar appears: go online, then fire the reconnect event.
      api.offline = false;
      api.postKeys.clear();
      conn.controller.add(true);

      // Let the auto-sync future complete.
      await Future<void>.delayed(const Duration(milliseconds: 50));

      // Exactly one POST per draft, replayed with the original keys.
      expect(api.postKeys.whereType<String>().toSet(), queuedKeys);
      expect(await repo.pendingDrafts(), isEmpty);
      // The reconnect-driven flush reported both drafts sent (feedback signal).
      expect(seenSyncedCounts, contains(2));

      await sub.cancel();
      await cubit.close();
      await conn.controller.close();
    },
  );
}
