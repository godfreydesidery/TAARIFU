/// Repository for "find my representatives" (PRD §22.6, UC-C01).
///
/// Responsibility: resolve a ward id to its representatives via the backend, and
/// cache the last result per ward so the citizen sees it instantly offline. This
/// is a public (`permitAll`) read — it must work for a Guest on a feature phone.
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'representative_models.dart';

/// Reads representative bundles by ward.
class RepresentativeRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  RepresentativeRepository({
    required ApiClient apiClient,
    required JsonCache cache,
  }) : _api = apiClient,
       _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  /// Finds the MP (Mbunge), Councillor (Diwani), and ward executive for [wardId].
  ///
  /// Maps to `GET /representatives/by-ward/{wardId}`. On success the raw bundle
  /// is cached under the ward key; on a network failure the cached bundle (if
  /// any) is returned, else the error propagates — the read-through offline
  /// pattern, applied to the platform's "front door" flow.
  Future<MyRepresentatives> findByWard(String wardId) async {
    final cacheKey = 'reps.by-ward.$wardId';
    try {
      final result = await _api.get<Map<String, dynamic>>(
        '/representatives/by-ward/$wardId',
        parser: (data) => (data as Map<String, dynamic>?) ?? const {},
      );
      await _cache.write(cacheKey, result.data);
      return MyRepresentatives.fromJson(result.data);
    } on Object {
      final cached = await _cache.read(cacheKey);
      if (cached is Map<String, dynamic>) {
        return MyRepresentatives.fromJson(cached);
      }
      rethrow;
    }
  }
}
