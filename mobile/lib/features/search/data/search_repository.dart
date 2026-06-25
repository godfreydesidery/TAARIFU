/// Repository for cross-entity discovery search (PRD discovery; ADR-0017,
/// `GET /search`).
///
/// Responsibility: turn a keyword query into one [ApiClient] call against the
/// real `SearchController` and map the ranked page to [SearchResult]s. This is a
/// public (`permitAll`) read — it must work for a Guest on a feature phone; the
/// backend applies its own server-side visibility gate (private/sensitive rows
/// are returned only to staff, never to a citizen), so the client never has to
/// reason about visibility.
///
/// Data-frugal (PRD §15): a blank/whitespace query makes NO network call (a
/// search is keyword-driven — the whole corpus is never enumerated), mirroring
/// the ward-picker's blank-query short-circuit.
library;

import '../../../core/network/api_client.dart';
import 'search_models.dart';

/// Runs discovery searches against the backend.
class SearchRepository {
  /// Creates the repository over an [ApiClient].
  SearchRepository({required ApiClient apiClient}) : _api = apiClient;

  final ApiClient _api;

  /// Searches across reps/orgs/announcements/categories/public reports for [query].
  ///
  /// Maps to `GET /search?q=&type=&page=&size=`. A blank query returns an empty
  /// list locally (no call — data-frugal). [kind], when supplied, narrows the
  /// search to one [SearchResultKind] via the backend `type` filter; the
  /// forward-compatible [SearchResultKind.unknown] has no API name and is treated
  /// as "all kinds".
  Future<List<SearchResult>> search(
    String query, {
    SearchResultKind? kind,
    int page = 0,
    int size = 20,
  }) async {
    final q = query.trim();
    if (q.isEmpty) {
      return const [];
    }
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/search',
      query: {
        'q': q,
        'type': ?kind?.apiName,
        'page': page,
        'size': size,
      },
      parser: _asMapList,
    );
    return result.data.map(SearchResult.fromJson).toList(growable: false);
  }

  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
