/// Wire model for the personalised feed, mirroring `FeedItemDto`
/// (backend `com.taarifu.communications.api.dto`).
library;

/// A single feed item (backend `FeedItemDto`): a lean announcement snippet.
///
/// Items are deliberately lean (snippet only, no body) for the feature-phone
/// data budget (PRD §15); the full announcement is fetched on tap.
class FeedItem {
  /// Creates a feed item.
  const FeedItem({
    required this.id,
    required this.title,
    required this.snippet,
    this.publishedAt,
  });

  /// Item public id (UUID string).
  final String id;

  /// Announcement title.
  final String title;

  /// Short snippet (no full body — data-frugal).
  final String snippet;

  /// Publish instant, or `null`.
  final DateTime? publishedAt;

  /// Parses a feed-item node.
  factory FeedItem.fromJson(Map<String, dynamic> json) => FeedItem(
    id: json['id'] as String,
    title: json['title'] as String? ?? '',
    snippet: json['snippet'] as String? ?? '',
    publishedAt: DateTime.tryParse(json['publishedAt'] as String? ?? ''),
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
