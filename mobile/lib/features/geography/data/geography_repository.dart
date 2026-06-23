/// Repository for the public civic-geography reads (regions, districts).
///
/// Responsibility: fetch reference geography through the [ApiClient] and apply
/// the offline-first read-through cache so a citizen on a dead network still
/// sees the last-known region list (PRD §15 offline-first; reference data
/// changes rarely, so caching it aggressively is both correct and data-frugal).
///
/// These endpoints are public (`permitAll`) — they work for a Guest with no
/// session (PRD §22.6 "front door").
library;

import '../../../core/network/api_client.dart';
import '../../../core/storage/json_cache.dart';
import 'geography_models.dart';

/// Reads civic geography reference data.
class GeographyRepository {
  /// Creates the repository over an [ApiClient] and a [JsonCache].
  GeographyRepository({required ApiClient apiClient, required JsonCache cache})
    : _api = apiClient,
      _cache = cache;

  final ApiClient _api;
  final JsonCache _cache;

  static const String _regionsCacheKey = 'geography.regions.page0';

  /// Lists regions (Mikoa), newest cache served immediately on a network miss.
  ///
  /// On success the raw list is cached; on an offline/transport failure the last
  /// cached list is returned if present, otherwise the error propagates. This is
  /// the read-through offline pattern the whole app reuses.
  Future<List<Region>> listRegions() async {
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/regions',
        query: {'page': 0, 'size': 100, 'sort': 'name,asc'},
        parser: _asMapList,
      );
      await _cache.write(_regionsCacheKey, result.data);
      return result.data.map(Region.fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(_regionsCacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(Region.fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  /// Resolves a GPS pin to its ward (Kata) for find-my-representatives.
  ///
  /// Maps to `GET /locations/resolve?lat=&lng=`. A `resolved = false` result is a
  /// normal, non-error outcome (the point matched no boundary) — the caller then
  /// uses manual selection (EI-7). Public; no session required.
  Future<LocationResolution> resolveByGps({
    required double lat,
    required double lng,
  }) async {
    final result = await _api.get<Map<String, dynamic>>(
      '/locations/resolve',
      query: {'lat': lat, 'lng': lng},
      parser: (data) => (data as Map<String, dynamic>?) ?? const {},
    );
    return LocationResolution.fromJson(result.data);
  }

  /// Lists the districts (Wilaya) of [regionId].
  Future<List<District>> listDistricts(String regionId) async {
    final cacheKey = 'geography.districts.$regionId';
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/regions/$regionId/districts',
        query: {'page': 0, 'size': 100, 'sort': 'name,asc'},
        parser: _asMapList,
      );
      await _cache.write(cacheKey, result.data);
      return result.data.map(District.fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(cacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(District.fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  /// Lists the wards (Kata) under [districtId] for the **manual ward picker**.
  ///
  /// Maps to `GET /districts/{districtId}/wards` (public; GPS-free). The result
  /// is cached per district so a citizen who once browsed a district's wards can
  /// still pick offline (PRD §15) — reference data changes rarely, so caching it
  /// is correct and data-frugal. Each [WardSummary] carries its council/district
  /// names so the picker can disambiguate same-named wards.
  Future<List<WardSummary>> listWardsInDistrict(
    String districtId, {
    int page = 0,
    int size = 100,
  }) async {
    final cacheKey = 'geography.wards.district.$districtId.p$page';
    try {
      final result = await _api.get<List<Map<String, dynamic>>>(
        '/districts/$districtId/wards',
        query: {'page': page, 'size': size, 'sort': 'name,asc'},
        parser: _asMapList,
      );
      await _cache.write(cacheKey, result.data);
      return result.data.map(WardSummary.fromJson).toList(growable: false);
    } on Object {
      final cached = await _cache.read(cacheKey);
      if (cached is List) {
        return cached
            .whereType<Map<String, dynamic>>()
            .map(WardSummary.fromJson)
            .toList(growable: false);
      }
      rethrow;
    }
  }

  /// Searches wards (Kata) by name prefix for the **manual ward picker**.
  ///
  /// Maps to `GET /wards?q=&districtId=` (public; GPS-free). A blank [query]
  /// short-circuits to an empty list — the backend returns an empty page for a
  /// blank `q` (a picker must not pull the national ward table), so we save the
  /// round-trip and the data (PRD §15). [districtId], when supplied, scopes the
  /// search to one district. Search is intentionally NOT cached: it is keystroke
  /// driven and unbounded, so caching every prefix would bloat storage for
  /// little gain (the district listing above is the offline path).
  Future<List<WardSummary>> searchWards(
    String query, {
    String? districtId,
    int size = 20,
  }) async {
    final q = query.trim();
    if (q.isEmpty) {
      return const [];
    }
    final result = await _api.get<List<Map<String, dynamic>>>(
      '/wards',
      query: {
        'q': q,
        if (districtId != null) 'districtId': districtId,
        'page': 0,
        'size': size,
        'sort': 'name,asc',
      },
      parser: _asMapList,
    );
    return result.data.map(WardSummary.fromJson).toList(growable: false);
  }

  /// Coerces the raw `data` list node into a list of JSON maps.
  static List<Map<String, dynamic>> _asMapList(Object? data) {
    if (data is List) {
      return data.whereType<Map<String, dynamic>>().toList(growable: false);
    }
    return const [];
  }
}
