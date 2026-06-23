/// Wire models for the representatives reads, mirroring the backend DTOs in
/// `com.taarifu.institutions.api.dto` (RepresentativeController).
///
/// Swahili civic terms preserved: Mbunge (MP), Diwani (Councillor), Mtendaji wa
/// Kata (ward executive) — CLAUDE.md §8.
library;

/// A representative summary (backend `RepresentativeSummaryDto`).
///
/// Only the fields the citizen UI needs are surfaced strongly; the rest are kept
/// for completeness so the model is a faithful mirror of the contract.
class RepresentativeSummary {
  /// Creates a summary.
  const RepresentativeSummary({
    required this.id,
    required this.type,
    required this.status,
    this.profileId,
    this.mandate,
    this.partyName,
    this.partyAbbrev,
    this.constituencyName,
    this.wardName,
    this.legislature,
  });

  /// Representative public id (UUID string).
  final String id;

  /// The linked profile's public id, or `null`.
  final String? profileId;

  /// Type: `MP` / `COUNCILLOR` / `WARD_EXEC`.
  final String type;

  /// Status: `SITTING` / `FORMER` / `PENDING_VERIFICATION`.
  final String status;

  /// Mandate description, or `null`.
  final String? mandate;

  /// Political party name, or `null` (independents / unset).
  final String? partyName;

  /// Party abbreviation, or `null`.
  final String? partyAbbrev;

  /// Constituency (Jimbo) name, or `null`.
  final String? constituencyName;

  /// Ward (Kata) name, or `null`.
  final String? wardName;

  /// Legislature label, or `null`.
  final String? legislature;

  /// Whether this representative is currently sitting (vs FORMER/PENDING).
  bool get isSitting => status == 'SITTING';

  /// Parses a summary node.
  factory RepresentativeSummary.fromJson(Map<String, dynamic> json) =>
      RepresentativeSummary(
        id: json['id'] as String,
        profileId: json['profileId'] as String?,
        type: json['type'] as String? ?? '',
        status: json['status'] as String? ?? '',
        mandate: json['mandate'] as String?,
        partyName: json['partyName'] as String?,
        partyAbbrev: json['partyAbbrev'] as String?,
        constituencyName: json['constituencyName'] as String?,
        wardName: json['wardName'] as String?,
        legislature: json['legislature'] as String?,
      );
}

/// The "find my representatives" bundle for a ward (backend `MyRepresentativesDto`).
///
/// Carries the ward + its current constituency context and the resolved reps:
/// the MP (Mbunge, via Ward→Constituency), councillors (Diwani), and any ward
/// executives. Any of these may be absent — the backend never hard-fails on a
/// missing rep (PRD §22.6), and the UI shows a "none found" state per slot.
class MyRepresentatives {
  /// Creates the bundle.
  const MyRepresentatives({
    required this.wardName,
    this.constituencyName,
    this.mp,
    this.councillors = const [],
    this.wardExecutives = const [],
  });

  /// The ward (Kata) name.
  final String wardName;

  /// The current constituency (Jimbo) name, or `null`.
  final String? constituencyName;

  /// The Member of Parliament (Mbunge), or `null` if none resolved.
  final RepresentativeSummary? mp;

  /// The councillors (Diwani) for the ward.
  final List<RepresentativeSummary> councillors;

  /// The ward/village executive officers.
  final List<RepresentativeSummary> wardExecutives;

  /// Parses the bundle node.
  factory MyRepresentatives.fromJson(Map<String, dynamic> json) {
    List<RepresentativeSummary> list(Object? raw) => raw is List
        ? raw
              .whereType<Map<String, dynamic>>()
              .map(RepresentativeSummary.fromJson)
              .toList(growable: false)
        : const [];
    final mpNode = json['mp'];
    return MyRepresentatives(
      wardName: json['wardName'] as String? ?? '',
      constituencyName: json['constituencyName'] as String?,
      mp: mpNode is Map<String, dynamic>
          ? RepresentativeSummary.fromJson(mpNode)
          : null,
      councillors: list(json['councillors']),
      wardExecutives: list(json['wardExecutives']),
    );
  }
}
