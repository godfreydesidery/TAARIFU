/// Tests for discovery search (PRD discovery; ADR-0017).
///
/// Covers the two invariants that matter on a tiny data budget and a flaky link:
///   * a blank/whitespace query makes NO network call (data-frugal) and resolves
///     to the empty result set;
///   * a real query hits `GET /search` with `q` (+ `type` when a kind filter is
///     set) and maps the ranked DTOs, degrading an unknown entity type to
///     [SearchResultKind.unknown] rather than failing the page.
/// Plus the cubit's debounce + kind-filter behaviour over a fake repository.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/features/search/bloc/search_cubit.dart';
import 'package:taarifu_citizen/features/search/data/search_models.dart';
import 'package:taarifu_citizen/features/search/data/search_repository.dart';

/// A fake [ApiClient] that records each GET's path + query and returns a fixed
/// ranked page (so the repository's mapping + query shaping can be asserted).
class _FakeApiClient implements ApiClient {
  final List<({String path, Map<String, dynamic>? query})> gets = [];
  List<Map<String, dynamic>> result = const [];

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async {
    gets.add((path: path, query: query));
    return ApiResult<T>(data: parser(result));
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

  @override
  Future<ApiResult<T>> delete<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async => throw UnimplementedError();
}

/// A fake repository the cubit drives against, recording queries it was asked.
class _FakeSearchRepository implements SearchRepository {
  final List<({String query, SearchResultKind? kind})> calls = [];
  List<SearchResult> toReturn = const [];
  Object? error;

  @override
  Future<List<SearchResult>> search(
    String query, {
    SearchResultKind? kind,
    int page = 0,
    int size = 20,
  }) async {
    calls.add((query: query, kind: kind));
    if (error != null) throw error!;
    return toReturn;
  }
}

void main() {
  group('SearchRepository', () {
    late _FakeApiClient api;
    late SearchRepository repo;

    setUp(() {
      api = _FakeApiClient();
      repo = SearchRepository(apiClient: api);
    });

    test('a blank query makes NO call and returns empty (data-frugal)', () async {
      final results = await repo.search('   ');
      expect(results, isEmpty);
      expect(api.gets, isEmpty);
    });

    test('a real query hits /search with q and maps ranked results', () async {
      api.result = [
        {
          'entityType': 'REPRESENTATIVE',
          'entityPublicId': 'rep-1',
          'title': 'Mbunge wa Kinondoni',
          'snippet': 'CCM',
          'rank': 0.9,
        },
        {
          'entityType': 'ANNOUNCEMENT',
          'entityPublicId': 'ann-1',
          'title': 'Maji Tegeta',
          'rank': 0.5,
        },
      ];
      final results = await repo.search('maji');
      expect(api.gets.single.path, '/search');
      expect(api.gets.single.query?['q'], 'maji');
      // No type param when no kind filter is set.
      expect(api.gets.single.query?.containsKey('type'), isFalse);
      expect(results, hasLength(2));
      expect(results.first.kind, SearchResultKind.representative);
      expect(results[1].kind, SearchResultKind.announcement);
    });

    test('a kind filter is sent as the backend type param', () async {
      await repo.search('barabara', kind: SearchResultKind.publicReport);
      expect(api.gets.single.query?['type'], 'PUBLIC_REPORT');
    });

    test('an unknown entity type degrades to unknown (forward-compatible)',
        () async {
      api.result = [
        {'entityType': 'GALAXY', 'entityPublicId': 'x', 'title': 'x'},
      ];
      final results = await repo.search('x');
      expect(results.single.kind, SearchResultKind.unknown);
    });
  });

  group('SearchCubit', () {
    late _FakeSearchRepository repo;

    setUp(() => repo = _FakeSearchRepository());

    blocTest<SearchCubit, SearchState>(
      'a blank query stays idle with no repository call',
      build: () => SearchCubit(repository: repo),
      act: (cubit) => cubit.queryChanged('   '),
      expect: () => [
        isA<SearchState>().having((s) => s.status, 'status', SearchStatus.idle),
      ],
      verify: (_) => expect(repo.calls, isEmpty),
    );

    blocTest<SearchCubit, SearchState>(
      'a real query debounces then loads results',
      build: () => SearchCubit(
        repository: repo..toReturn = const [
          SearchResult(
            kind: SearchResultKind.organisation,
            entityPublicId: 'o-1',
            title: 'DAWASA',
          ),
        ],
        // A tiny debounce so the test resolves quickly.
        debounce: const Duration(milliseconds: 10),
      ),
      act: (cubit) => cubit.queryChanged('dawasa'),
      wait: const Duration(milliseconds: 40),
      expect: () => [
        isA<SearchState>().having((s) => s.status, 'status', SearchStatus.loading),
        isA<SearchState>()
            .having((s) => s.status, 'status', SearchStatus.loaded)
            .having((s) => s.results.length, 'results', 1),
      ],
      verify: (_) => expect(repo.calls.single.query, 'dawasa'),
    );

    blocTest<SearchCubit, SearchState>(
      'setting a kind filter re-runs the active query immediately',
      build: () => SearchCubit(repository: repo, debounce: Duration.zero),
      seed: () => const SearchState(
        status: SearchStatus.loaded,
        query: 'maji',
      ),
      act: (cubit) => cubit.setKind(SearchResultKind.announcement),
      wait: const Duration(milliseconds: 20),
      verify: (_) {
        expect(repo.calls, isNotEmpty);
        expect(repo.calls.last.kind, SearchResultKind.announcement);
        expect(repo.calls.last.query, 'maji');
      },
    );
  });
}
