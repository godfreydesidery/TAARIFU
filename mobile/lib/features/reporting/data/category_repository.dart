/// Repository for the public issue-category picker (PRD Appendix D, UC-B14).
///
/// Responsibility: fetch the active issue-category taxonomy through the
/// [ApiClient] and cache it (read-through) so the report form's category picker
/// renders instantly and survives a dead network — categories change rarely, so
/// caching them aggressively is correct and data-frugal (PRD §15). The list read
/// is public (`permitAll`); no session is required to browse categories.
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'reporting_models.dart';

/// Reads the issue-category taxonomy for the report picker.
class CategoryRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  CategoryRepository({required ApiClient apiClient, required JsonCache cache})
    : _api = apiClient,
      _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  static const String _cacheKey = 'reporting.categories.page0';

  /// Lists active categories for the picker.
  ///
  /// Maps to `GET /issue-categories?page=0&size=100&sort=name,asc`. On a network
  /// failure the last cached taxonomy is returned so a citizen can still start a
  /// report offline (the submit then queues — UC-D03).
  Future<List<IssueCategory>> listActive() async {
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/issue-categories',
        query: {'page': 0, 'size': 100, 'sort': 'name,asc'},
        parser: _asMapList,
      );
      await _cache.write(_cacheKey, result.data);
      return result.data.map(IssueCategory.fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(_cacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(IssueCategory.fromJson)
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
