/// The feed-item detail screen (Taarifa) — US-4.2.
///
/// Renders a single announcement from the [FeedItem] the citizen tapped. The feed
/// is intentionally lean (snippet only) for the data budget; the PRD's "full
/// announcement on tap" needs a public `GET /announcements/{id}` that does not
/// yet exist on the backend (only the lean feed item and an author-scoped
/// `/announcements/mine`). That is flagged under CENTRAL INTEGRATION NEEDS — until
/// it lands, this screen shows the snippet already carried in the feed (no extra
/// data fetch, which is also the most data-frugal choice).
library;

import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';
import '../data/feed_models.dart';

/// Shows one feed item's content.
class FeedDetailScreen extends StatelessWidget {
  /// Creates the screen for [item].
  const FeedDetailScreen({required this.item, super.key});

  /// The tapped feed item.
  final FeedItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.feedDetailTitle)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Text(item.title, style: Theme.of(context).textTheme.headlineSmall),
          if (item.publishedAt != null) ...[
            const SizedBox(height: 4),
            Text(
              item.publishedAt!.toLocal().toString().split('.').first,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ],
          const SizedBox(height: 16),
          Text(item.snippet, style: Theme.of(context).textTheme.bodyLarge),
        ],
      ),
    );
  }
}
