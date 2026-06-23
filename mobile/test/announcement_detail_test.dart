/// Tests the announcement-detail read (US-4.2, `GET /announcements/{id}`): the
/// [FeedRepository.getAnnouncement] read-through cache, the locale-aware body
/// selection on [Announcement], and the [AnnouncementDetailCubit] states.
library;

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:taarifu_citizen/core/network/api_client.dart';
import 'package:taarifu_citizen/core/network/api_exception.dart';
import 'package:taarifu_citizen/core/storage/json_cache.dart';
import 'package:taarifu_citizen/features/feed/bloc/announcement_detail_cubit.dart';
import 'package:taarifu_citizen/features/feed/data/feed_models.dart';
import 'package:taarifu_citizen/features/feed/data/feed_repository.dart';

/// A fake [ApiClient] returning a canned announcement map, or throwing offline.
class _FakeApiClient implements ApiClient {
  bool offline = false;
  int getCount = 0;
  Map<String, dynamic> announcement = const {};

  @override
  Future<ApiResult<T>> get<T>(
    String path, {
    Map<String, dynamic>? query,
    required T Function(Object? data) parser,
  }) async {
    getCount++;
    if (offline) {
      throw const OfflineException();
    }
    return ApiResult<T>(data: parser(announcement));
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

const _dto = {
  'id': 'a-1',
  'title': 'Maji kukatika',
  'bodySw': 'Maji yatakatika kesho kuanzia saa mbili asubuhi.',
  'bodyEn': 'Water will be cut tomorrow from 8am.',
  'publishAt': '2026-06-20T05:00:00Z',
};

void main() {
  group('Announcement model', () {
    final a = Announcement.fromJson(_dto);

    test('bodyForLocale prefers Swahili by default (Swahili-first)', () {
      expect(a.bodyForLocale('sw'), startsWith('Maji yatakatika'));
    });

    test('bodyForLocale returns English when locale is en and body exists', () {
      expect(a.bodyForLocale('en'), startsWith('Water will be cut'));
    });

    test('bodyForLocale falls back to Swahili when English body is absent', () {
      final swOnly = Announcement.fromJson({
        ..._dto,
        'bodyEn': null,
      });
      expect(swOnly.bodyForLocale('en'), startsWith('Maji yatakatika'));
    });
  });

  group('FeedRepository.getAnnouncement', () {
    late _FakeApiClient api;
    late FeedRepository repo;

    setUp(() {
      api = _FakeApiClient()..announcement = Map.of(_dto);
      repo = FeedRepository(apiClient: api, cache: InMemoryJsonCache());
    });

    test('fetches and maps the full announcement', () async {
      final a = await repo.getAnnouncement('a-1');
      expect(a.id, 'a-1');
      expect(a.title, 'Maji kukatika');
      expect(a.publishAt, isNotNull);
    });

    test('serves the cached announcement when offline', () async {
      await repo.getAnnouncement('a-1'); // warm cache
      api.offline = true;
      final a = await repo.getAnnouncement('a-1');
      expect(a.title, 'Maji kukatika');
    });

    test('propagates the error when offline with no cache', () async {
      api.offline = true;
      expect(() => repo.getAnnouncement('a-1'), throwsA(isA<OfflineException>()));
    });
  });

  group('AnnouncementDetailCubit', () {
    late _FakeApiClient api;
    late FeedRepository repo;

    setUp(() {
      api = _FakeApiClient()..announcement = Map.of(_dto);
      repo = FeedRepository(apiClient: api, cache: InMemoryJsonCache());
    });

    blocTest<AnnouncementDetailCubit, AnnouncementDetailState>(
      'load → loading then loaded with the full announcement',
      build: () =>
          AnnouncementDetailCubit(repository: repo, announcementId: 'a-1'),
      act: (cubit) => cubit.load(),
      expect: () => [
        isA<AnnouncementDetailState>().having(
          (s) => s.status,
          'status',
          AnnouncementDetailStatus.loading,
        ),
        isA<AnnouncementDetailState>()
            .having(
              (s) => s.status,
              'status',
              AnnouncementDetailStatus.loaded,
            )
            .having((s) => s.announcement?.id, 'id', 'a-1'),
      ],
    );

    blocTest<AnnouncementDetailCubit, AnnouncementDetailState>(
      'load failure (offline, no cache) surfaces the failure status',
      setUp: () => api.offline = true,
      build: () =>
          AnnouncementDetailCubit(repository: repo, announcementId: 'a-1'),
      act: (cubit) => cubit.load(),
      verify: (cubit) {
        expect(cubit.state.status, AnnouncementDetailStatus.failure);
        expect(cubit.state.error, isA<OfflineException>());
      },
    );
  });
}
