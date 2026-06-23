/// Wire models for the public geography reads, mirroring the backend DTOs in
/// `com.taarifu.geography.api.dto` (GeographyController, LocationController).
///
/// Swahili civic terms are preserved in the doc comments (Mkoa/Wilaya/Kata) per
/// CLAUDE.md §8.
library;

/// A region — Mkoa (backend `RegionDto`): `{ id, code, name }`.
class Region {
  /// Creates a region.
  const Region({required this.id, required this.code, required this.name});

  /// Public id (UUID string).
  final String id;

  /// Human-readable region code.
  final String code;

  /// Region name (e.g. "Dodoma").
  final String name;

  /// Parses a region node.
  factory Region.fromJson(Map<String, dynamic> json) => Region(
    id: json['id'] as String,
    code: json['code'] as String? ?? '',
    name: json['name'] as String? ?? '',
  );
}

/// A ward — Kata (backend `WardDto`): `{ id, code, name, parentId }`.
///
/// The ward is the minimum pin granularity that resolves a citizen's MP, Diwani
/// and report routing (PRD §9.0).
class Ward {
  /// Creates a ward.
  const Ward({required this.id, required this.code, required this.name});

  /// Public id (UUID string) — the input to find-my-representatives.
  final String id;

  /// Official ward code.
  final String code;

  /// Ward display name (e.g. "Mengwe").
  final String name;

  /// Parses a ward node.
  factory Ward.fromJson(Map<String, dynamic> json) => Ward(
    id: json['id'] as String,
    code: json['code'] as String? ?? '',
    name: json['name'] as String? ?? '',
  );
}

/// The result of GPS→ward resolution (backend `LocationResolutionDto`).
///
/// When [resolved] is false the pin matched no ward boundary and the client
/// falls back to manual selection (EI-7 graceful degradation). Only the fields
/// the citizen UI needs ([resolved], [ward]) are surfaced strongly here.
class LocationResolution {
  /// Creates a resolution result.
  const LocationResolution({required this.resolved, this.ward});

  /// Whether the GPS point matched a ward boundary.
  final bool resolved;

  /// The resolved ward, or `null` when not [resolved].
  final Ward? ward;

  /// Parses a resolution node.
  factory LocationResolution.fromJson(Map<String, dynamic> json) {
    final wardNode = json['ward'];
    return LocationResolution(
      resolved: json['resolved'] == true,
      ward: wardNode is Map<String, dynamic> ? Ward.fromJson(wardNode) : null,
    );
  }
}

/// A ward search/list result for the **manual ward picker** (backend
/// `WardSummaryDto`): `{ id, code, name, councilName, districtName }`.
///
/// WHY a leaner shape than [Ward]: the picker shows a human label, not parent
/// UUIDs — so this carries the council (Halmashauri) and district (Wilaya)
/// *names* the backend denormalises through the closure table, letting the UI
/// disambiguate two same-named wards (e.g. two "Mji Mpya") without a second
/// round-trip (data-frugal, PRD §15). The [id] is the ward public id every flow
/// pins by (report form, profile locations, find-my-rep) — replacing the
/// hand-typed ward UUID.
class WardSummary {
  /// Creates a ward summary.
  const WardSummary({
    required this.id,
    required this.code,
    required this.name,
    this.councilName,
    this.districtName,
  });

  /// Ward public id (UUID string) — the value clients pass when pinning.
  final String id;

  /// Official ward code.
  final String code;

  /// Ward display name (e.g. "Mengwe").
  final String name;

  /// Parent council/LGA (Halmashauri) name, or `null` if unresolved.
  final String? councilName;

  /// District (Wilaya) ancestor name, or `null` if unresolved.
  final String? districtName;

  /// A breadcrumb-style label disambiguating same-named wards, e.g.
  /// "Mengwe · Moshi DC · Kilimanjaro". Falls back gracefully when ancestors
  /// are missing (tolerates incomplete seed chains).
  String get qualifiedLabel => [
    name,
    if (councilName != null && councilName!.isNotEmpty) councilName!,
    if (districtName != null && districtName!.isNotEmpty) districtName!,
  ].join(' · ');

  /// Parses a ward-summary node.
  factory WardSummary.fromJson(Map<String, dynamic> json) => WardSummary(
    id: json['id'] as String,
    code: json['code'] as String? ?? '',
    name: json['name'] as String? ?? '',
    councilName: json['councilName'] as String?,
    districtName: json['districtName'] as String?,
  );
}

/// A district — Wilaya (backend `DistrictDto`): `{ id, code, name, regionId }`.
class District {
  /// Creates a district.
  const District({
    required this.id,
    required this.code,
    required this.name,
    required this.regionId,
  });

  /// Public id (UUID string).
  final String id;

  /// Human-readable district code.
  final String code;

  /// District name.
  final String name;

  /// Parent region's public id.
  final String regionId;

  /// Parses a district node.
  factory District.fromJson(Map<String, dynamic> json) => District(
    id: json['id'] as String,
    code: json['code'] as String? ?? '',
    name: json['name'] as String? ?? '',
    regionId: json['regionId'] as String? ?? '',
  );
}
