/// Wire models for the cross-entity discovery endpoint, mirroring the backend
/// `SearchResultDto` (`com.taarifu.search.api.dto`) and `SearchEntityType`.
library;

import '../../../l10n/app_localizations.dart';

/// The kind of a search result, used to badge it and route the tap to the right
/// owning surface (the search index never inlines the full record — the client
/// re-reads it from the owner by [SearchResult.entityPublicId], ADR-0013).
///
/// WHY a closed enum with an [SearchResultKind.unknown] fallback: the backend's
/// `SearchEntityType` is append-only; a value this client predates degrades to a
/// neutral, non-tappable result rather than crashing (forward-compatible, EI-7).
enum SearchResultKind {
  /// An elected representative — Mbunge (MP) or Diwani (Councillor).
  representative,

  /// A government/parastatal/private responder organisation.
  organisation,

  /// A published civic announcement.
  announcement,

  /// An issue category (e.g. Maji/Water, Barabara/Roads).
  issueCategory,

  /// A publicly-visible issue report (ticket).
  publicReport,

  /// An entity kind this client build does not yet understand.
  unknown;

  /// Maps the backend `entityType` name to a [SearchResultKind], defaulting to
  /// [unknown] for anything unrecognised (so a newer server never breaks an
  /// older app).
  static SearchResultKind fromCode(String? code) => switch (code) {
    'REPRESENTATIVE' => SearchResultKind.representative,
    'ORGANISATION' => SearchResultKind.organisation,
    'ANNOUNCEMENT' => SearchResultKind.announcement,
    'ISSUE_CATEGORY' => SearchResultKind.issueCategory,
    'PUBLIC_REPORT' => SearchResultKind.publicReport,
    _ => SearchResultKind.unknown,
  };

  /// The backend `SearchEntityType` name for this kind, or `null` for [unknown]
  /// (used as the optional `type` filter on `GET /search`).
  String? get apiName => switch (this) {
    SearchResultKind.representative => 'REPRESENTATIVE',
    SearchResultKind.organisation => 'ORGANISATION',
    SearchResultKind.announcement => 'ANNOUNCEMENT',
    SearchResultKind.issueCategory => 'ISSUE_CATEGORY',
    SearchResultKind.publicReport => 'PUBLIC_REPORT',
    SearchResultKind.unknown => null,
  };

  /// The localised filter-chip / badge label for this kind.
  String label(AppLocalizations l10n) => switch (this) {
    SearchResultKind.representative => l10n.searchKindRepresentative,
    SearchResultKind.organisation => l10n.searchKindOrganisation,
    SearchResultKind.announcement => l10n.searchKindAnnouncement,
    SearchResultKind.issueCategory => l10n.searchKindCategory,
    SearchResultKind.publicReport => l10n.searchKindReport,
    SearchResultKind.unknown => l10n.searchKindOther,
  };
}

/// One ranked discovery result (backend `SearchResultDto`).
///
/// Lean by design (PRD §15): a typed discriminator, the owner's public id (the
/// re-read key when tapped), a display title, one locale-resolved snippet, and
/// the area/category ids for "filter by this". Carries NO PII and no internal
/// ids — only public display fields + opaque UUIDs (PRD §18).
class SearchResult {
  /// Creates a result.
  const SearchResult({
    required this.kind,
    required this.entityPublicId,
    required this.title,
    this.snippet,
    this.areaId,
    this.categoryId,
    this.rank = 0,
  });

  /// The kind of matched entity (drives the badge and the tap target).
  final SearchResultKind kind;

  /// The source aggregate's public id — the client's re-read key.
  final String entityPublicId;

  /// The display label.
  final String title;

  /// The snippet resolved to the caller's locale, or `null`.
  final String? snippet;

  /// The matched row's area public id, or `null`.
  final String? areaId;

  /// The matched row's category public id, or `null`.
  final String? categoryId;

  /// The FTS relevance score (higher is more relevant); results arrive ordered.
  final double rank;

  /// Parses a `SearchResultDto` node. Unknown kinds degrade to
  /// [SearchResultKind.unknown] rather than failing the whole page.
  factory SearchResult.fromJson(Map<String, dynamic> json) => SearchResult(
    kind: SearchResultKind.fromCode(json['entityType'] as String?),
    entityPublicId: json['entityPublicId'] as String? ?? '',
    title: json['title'] as String? ?? '',
    snippet: (json['snippet'] as String?)?.trim().isEmpty ?? true
        ? null
        : json['snippet'] as String?,
    areaId: json['areaId'] as String?,
    categoryId: json['categoryId'] as String?,
    rank: (json['rank'] as num?)?.toDouble() ?? 0,
  );
}
