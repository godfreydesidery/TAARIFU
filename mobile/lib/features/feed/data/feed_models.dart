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
