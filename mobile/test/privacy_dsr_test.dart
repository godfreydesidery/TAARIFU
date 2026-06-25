/// Tests for PDPA data-subject-request self-service (PRD §18, §25.1; ADR-0016).
///
/// Covers: the repository maps `/privacy/dsr/**` correctly (no account id ever
/// sent — the subject is bound server-side), and the cubit refreshes the list
/// after opening a request and surfaces a backend block (e.g. an active staff
/// role on erasure) as an error rather than mutating the list.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/features/privacy/bloc/dsr_cubit.dart';
import 'package:taarifu_citizen/features/privacy/data/dsr_models.dart';
import 'package:taarifu_citizen/features/privacy/data/dsr_repository.dart';

/// A fake [ApiClient] recording GET/POST paths and bodies; configurable to fail.
class _FakeApiClient implements ApiClient {
  final List<String> getPaths = [];
  final List<({String path, Object? body})> posts = [];
  Object? postError;
  List<Map<String, dynamic>> mine = const [];

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async {
    getPaths.add(path);
    return ApiResult<T>(data: parser(mine));
  }

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async {
    posts.add((path: path, body: body));
    if (postError != null) throw postError!;
    return ApiResult<T>(
      data: parser({
        'publicId': 'dsr-1',
        'type': path.endsWith('erasure') ? 'ERASURE' : 'ACCESS',
        'status': 'RECEIVED',
      }),
    );
  }

  @override
  Future<ApiResult<T>> put<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();

  @override
  Future<ApiResult<T>> delete<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();
}

/// A fake repository the cubit drives against.
class _FakeDsrRepository implements DsrRepository {
  List<DataSubjectRequest> mine = const [];
  bool erasureBlocked = false;

  @override
  Future<List<DataSubjectRequest>> listMine() async => mine;

  @override
  Future<DataSubjectRequest> requestAccess() async {
    mine = const [
      DataSubjectRequest(id: 'a-1', type: DsrType.access, status: 'RECEIVED'),
    ];
    return mine.first;
  }

  @override
  Future<DataSubjectRequest> requestErasure() async {
    if (erasureBlocked) throw StateError('blocked: active staff role');
    mine = const [
      DataSubjectRequest(id: 'e-1', type: DsrType.erasure, status: 'RECEIVED'),
    ];
    return mine.first;
  }
}

void main() {
  group('DsrRepository', () {
    late _FakeApiClient api;
    late DsrRepository repo;

    setUp(() {
      api = _FakeApiClient();
      repo = DsrRepository(apiClient: api);
    });

    test('requestAccess posts to /privacy/dsr/access with no account id',
        () async {
      final dsr = await repo.requestAccess();
      expect(api.posts.single.path, '/privacy/dsr/access');
      // The subject is bound from the token — the client sends no id/body.
      expect(api.posts.single.body, isNull);
      expect(dsr.type, DsrType.access);
    });

    test('requestErasure posts to /privacy/dsr/erasure', () async {
      final dsr = await repo.requestErasure();
      expect(api.posts.single.path, '/privacy/dsr/erasure');
      expect(dsr.type, DsrType.erasure);
    });

    test('listMine reads /privacy/dsr/mine and maps requests', () async {
      api.mine = [
        {'publicId': 'd-1', 'type': 'ACCESS', 'status': 'ACKNOWLEDGED'},
      ];
      final list = await repo.listMine();
      expect(api.getPaths.single, '/privacy/dsr/mine');
      expect(list.single.status, 'ACKNOWLEDGED');
    });
  });

  group('DsrCubit', () {
    late _FakeDsrRepository repo;

    setUp(() => repo = _FakeDsrRepository());

    blocTest<DsrCubit, DsrState>(
      'opening an access request refreshes the list and reports the action',
      build: () => DsrCubit(repository: repo),
      seed: () => const DsrState(status: DsrStatus.loaded),
      act: (cubit) => cubit.requestAccess(),
      expect: () => [
        isA<DsrState>().having((s) => s.actionInFlight, 'inFlight', true),
        isA<DsrState>()
            .having((s) => s.actionInFlight, 'inFlight', false)
            .having((s) => s.action, 'action', DsrAction.accessOpened)
            .having((s) => s.requests.length, 'requests', 1),
      ],
    );

    blocTest<DsrCubit, DsrState>(
      'a blocked erasure surfaces an error and does not mutate the list',
      build: () => DsrCubit(repository: repo..erasureBlocked = true),
      seed: () => const DsrState(status: DsrStatus.loaded),
      act: (cubit) => cubit.requestErasure(),
      expect: () => [
        isA<DsrState>().having((s) => s.actionInFlight, 'inFlight', true),
        isA<DsrState>()
            .having((s) => s.actionInFlight, 'inFlight', false)
            .having((s) => s.error, 'error', isNotNull)
            .having((s) => s.requests, 'requests', isEmpty),
      ],
    );
  });
}
