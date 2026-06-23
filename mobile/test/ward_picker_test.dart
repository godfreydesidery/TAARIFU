/// Tests the manual ward picker end-to-end below the UI: the [GeographyRepository]
/// ward listing/search against the new backend endpoints (`GET
/// /districts/{id}/wards`, `GET /wards?q=&districtId=`), and the
/// [WardPickerCubit] state machine over them.
///
/// These pin the replacement of the hand-typed ward UUID (PRD §9.0, §22.6): a
/// blank search makes no call (data-frugal), a district listing is cached for
/// offline reuse, and search results flow through the cubit's status states.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/network/api_exception.dart';
import 'package:taarifu_citizen/core/storage/json_cache.dart';
import 'package:taarifu_citizen/features/geography/bloc/ward_picker_cubit.dart';
import 'package:taarifu_citizen/features/geography/data/geography_repository.dart';

/// A controllable fake [ApiClient] recording GET paths + queries, returning
/// canned `data` lists per path, and optionally throwing [OfflineException].
class _FakeApiClient implements ApiClient {
  bool offline = false;
  final List<({String path, Map<String, dynamic>? query})> gets = [];

  /// path → the raw `data` list to return.
  final Map<String, List<Map<String, dynamic>>> responses = {};

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async {
    gets.add((path: path, query: query));
    if (offline) {
      throw const OfflineException();
    }
    final data = responses[path] ?? const <Map<String, dynamic>>[];
    return ApiResult<T>(data: parser(data));
  }

  @override
  Future<ApiResult<T>> post<T>(
    String path, {
    Object? body,
    String? idempotencyKey,
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

Map<String, dynamic> _ward(String id, String name) => {
  'id': id,
  'code': 'W-$id',
  'name': name,
  'councilName': 'Kinondoni MC',
  'districtName': 'Kinondoni',
};

void main() {
  late _FakeApiClient api;
  late GeographyRepository repo;

  setUp(() {
    api = _FakeApiClient();
    repo = GeographyRepository(apiClient: api, cache: InMemoryJsonCache());
  });

  group('GeographyRepository ward picker', () {
    test('listWardsInDistrict hits /districts/{id}/wards and maps summaries',
        () async {
      api.responses['/districts/d-1/wards'] = [
        _ward('w-1', 'Mengwe'),
        _ward('w-2', 'Msasani'),
      ];
      final wards = await repo.listWardsInDistrict('d-1');
      expect(api.gets.single.path, '/districts/d-1/wards');
      expect(wards.map((w) => w.name), ['Mengwe', 'Msasani']);
      expect(wards.first.id, 'w-1');
      expect(wards.first.locationLabel, 'Kinondoni MC · Kinondoni');
    });

    test('listWardsInDistrict serves the cached list when offline', () async {
      api.responses['/districts/d-1/wards'] = [_ward('w-1', 'Mengwe')];
      await repo.listWardsInDistrict('d-1'); // warms the cache
      api.offline = true;
      final wards = await repo.listWardsInDistrict('d-1');
      expect(wards.map((w) => w.name), ['Mengwe']);
    });

    test('searchWards with a blank query makes NO call (data-frugal)', () async {
      final results = await repo.searchWards('   ');
      expect(results, isEmpty);
      expect(api.gets, isEmpty);
    });

    test('searchWards hits /wards with q (+ districtId when scoped)', () async {
      api.responses['/wards'] = [_ward('w-9', 'Mikocheni')];
      final results = await repo.searchWards('Miko', districtId: 'd-1');
      expect(api.gets.single.path, '/wards');
      expect(api.gets.single.query?['q'], 'Miko');
      expect(api.gets.single.query?['districtId'], 'd-1');
      expect(results.single.name, 'Mikocheni');
    });
  });

  group('WardPickerCubit', () {
    blocTest<WardPickerCubit, WardPickerState>(
      'selectDistrict lists wards (browse mode)',
      setUp: () {
        api.responses['/districts/d-1/wards'] = [_ward('w-1', 'Mengwe')];
      },
      build: () => WardPickerCubit(repository: repo),
      act: (cubit) => cubit.selectDistrict('d-1'),
      verify: (cubit) {
        expect(cubit.state.wardStatus, WardListStatus.loaded);
        expect(cubit.state.mode, WardListMode.browse);
        expect(cubit.state.wards.single.name, 'Mengwe');
      },
    );

    blocTest<WardPickerCubit, WardPickerState>(
      'search with text loads results (search mode)',
      setUp: () {
        api.responses['/wards'] = [_ward('w-9', 'Mikocheni')];
      },
      build: () => WardPickerCubit(repository: repo),
      act: (cubit) => cubit.search('Miko'),
      verify: (cubit) {
        expect(cubit.state.mode, WardListMode.search);
        expect(cubit.state.wardStatus, WardListStatus.loaded);
        expect(cubit.state.wards.single.id, 'w-9');
      },
    );

    blocTest<WardPickerCubit, WardPickerState>(
      'blank search returns to idle without a call',
      build: () => WardPickerCubit(repository: repo),
      act: (cubit) => cubit.search('   '),
      verify: (cubit) {
        expect(cubit.state.wardStatus, WardListStatus.idle);
        expect(cubit.state.wards, isEmpty);
        expect(api.gets, isEmpty);
      },
    );

    blocTest<WardPickerCubit, WardPickerState>(
      'a failed listing surfaces the failure status for retry',
      setUp: () => api.offline = true,
      build: () => WardPickerCubit(repository: repo),
      act: (cubit) => cubit.selectDistrict('d-1'),
      verify: (cubit) {
        expect(cubit.state.wardStatus, WardListStatus.failure);
        expect(cubit.state.error, isA<OfflineException>());
      },
    );
  });
}
