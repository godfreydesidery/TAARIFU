/// Wire model for the personalised feed, mirroring `FeedItemDto`
/// (backend `com.taarifu.communications.api.dto`).
library;

/// The kind of a feed item, used purely to style its card (badge + accent).
///
/// WHY a closed enum with an [FeedItemKind.announcement] fallback: the feed is a
/// mixed civic stream (announcements, public reports, petitions, polls). The
/// backend tags each item with a `kind`; an unknown/absent tag degrades to a
/// neutral announcement card rather than failing to render (EI-7, fail-safe).
enum FeedItemKind {
  /// An official announcement (the current backend default).
  announcement,

  /// A public citizen report surfaced in the area feed.
  report,

  /// A petition the citizen can view/sign.
  petition,

  /// A poll/survey the citizen can respond to.
  poll;

  /// Maps a backend `kind` string to a [FeedItemKind], defaulting to
  /// [announcement] for anything unrecognised (forward-compatible).
  static FeedItemKind fromCode(String? code) => switch (code?.toUpperCase()) {
    'REPORT' => FeedItemKind.report,
    'PETITION' => FeedItemKind.petition,
    'POLL' || 'SURVEY' => FeedItemKind.poll,
    _ => FeedItemKind.announcement,
  };
}

/// A single feed item (backend `FeedItemDto`): a lean civic-update snippet.
///
/// Items are deliberately lean (snippet only, no body) for the feature-phone
/// data budget (PRD §15); the full item is fetched on tap. The social fields
/// ([kind], [authorName], [areaName], [imageUrl], [reactionCount]) are all
/// OPTIONAL — the card renders elegantly whether or not the backend supplies
/// them, so this stays forward-compatible with the existing `/feed` contract and
/// never fabricates data the server did not send.
class FeedItem {
  /// Creates a feed item.
  const FeedItem({
    required this.id,
    required this.title,
    required this.snippet,
    this.publishedAt,
    this.kind = FeedItemKind.announcement,
    this.authorName,
    this.areaName,
    this.imageUrl,
    this.reactionCount = 0,
  });

  /// Item public id (UUID string).
  final String id;

  /// Announcement title.
  final String title;

  /// Short snippet (no full body — data-frugal).
  final String snippet;

  /// Publish instant, or `null`.
  final DateTime? publishedAt;

  /// The item's kind (drives the card badge + accent); defaults to announcement.
  final FeedItemKind kind;

  /// The author/issuer name (e.g. the council or representative), or `null`.
  final String? authorName;

  /// The geographic area label (Kata/Wilaya) the item relates to, or `null`.
  final String? areaName;

  /// An optional thumbnail/cover image URL. Honoured only when data-saver is off
  /// (the card decides) so a low-bundle citizen is never charged for imagery.
  final String? imageUrl;

  /// Engagement/reaction count, or 0 when the backend does not track it.
  final int reactionCount;

  /// Parses a feed-item node. Unknown/absent optional fields degrade gracefully.
  factory FeedItem.fromJson(Map<String, dynamic> json) => FeedItem(
    id: json['id'] as String,
    title: json['title'] as String? ?? '',
    snippet: json['snippet'] as String? ?? '',
    publishedAt: DateTime.tryParse(json['publishedAt'] as String? ?? ''),
    kind: FeedItemKind.fromCode(json['kind'] as String?),
    authorName: (json['authorName'] as String?)?.trim().isEmpty ?? true
        ? null
        : json['authorName'] as String?,
    areaName: (json['areaName'] as String?)?.trim().isEmpty ?? true
        ? null
        : json['areaName'] as String?,
    imageUrl: (json['imageUrl'] as String?)?.trim().isEmpty ?? true
        ? null
        : json['imageUrl'] as String?,
    reactionCount: (json['reactionCount'] as num?)?.toInt() ?? 0,
  );
}

/// A full announcement (backend `AnnouncementDto`, `GET /announcements/{id}`).
///
/// The detail fetched when a citizen taps a lean [FeedItem] (US-4.2): the full
/// body in **both languages** so the UI renders the recipient's locale (Swahili
/// first, English secondary — ADR-0010), with the publish timestamp. Only a
/// PUBLISHED, in-window announcement is ever returned (the backend 404s anything
/// in flight), so this is always safe, public civic data (PRD §22.6).
///
/// Only the fields the citizen detail needs are surfaced strongly; author/area
/// targeting and channel internals are intentionally omitted to keep the payload
/// and model lean (PRD §15 data-budget).
class Announcement {
  /// Creates an announcement.
  const Announcement({
    required this.id,
    required this.title,
    required this.bodySw,
    this.bodyEn,
    this.publishAt,
  });

  /// Announcement public id (UUID string).
  final String id;

  /// The headline.
  final String title;

  /// The Swahili body (the default-locale content — always present).
  final String bodySw;

  /// The English body, or `null` if the author wrote Swahili only.
  final String? bodyEn;

  /// When it went live (UTC), or `null`.
  final DateTime? publishAt;

  /// The body to render for [localeCode], preferring English only when both an
  /// English locale and a non-empty English body exist; otherwise the Swahili
  /// body (the Swahili-first default — never show an empty screen, EI-7).
  String bodyForLocale(String localeCode) {
    final en = bodyEn;
    if (localeCode.startsWith('en') && en != null && en.trim().isNotEmpty) {
      return en;
    }
    return bodySw;
  }

  /// Parses an `AnnouncementDto` node.
  factory Announcement.fromJson(Map<String, dynamic> json) => Announcement(
    id: json['id'] as String,
    title: json['title'] as String? ?? '',
    bodySw: json['bodySw'] as String? ?? '',
    bodyEn: json['bodyEn'] as String?,
    publishAt: DateTime.tryParse(json['publishAt'] as String? ?? ''),
  );
}
