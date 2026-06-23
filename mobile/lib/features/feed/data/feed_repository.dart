/// Repository for the citizen's personalised feed (PRD §12 UC-G04).
///
/// Responsibility: fetch the authenticated citizen's feed page through the
/// [ApiClient] and cache it for offline display. The feed requires a session
/// (T1, `isAuthenticated`) — a Guest gets a sign-in prompt instead of a call.
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'feed_models.dart';

/// Reads the personalised feed.
class FeedRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  FeedRepository({required ApiClient apiClient, required JsonCache cache})
    : _api = apiClient,
      _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  static const String _cacheKey = 'feed.page0';

  /// Loads the first feed page (newest first), with read-through caching.
  ///
  /// Maps to `GET /feed?page=0&size=20`. On a network failure the last cached
  /// page is shown so the citizen still sees recent updates offline (PRD §15).
  Future<List<FeedItem>> loadFirstPage() async {
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/feed',
        query: {'page': 0, 'size': 20},
        parser: _asMapList,
      );
      await _cache.write(_cacheKey, result.data);
      return result.data.map(FeedItem.fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(_cacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(FeedItem.fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
