/// The announcement-detail screen (Taarifa) — US-4.2.
///
/// The citizen taps a lean [FeedItem] (snippet only) and this screen fetches the
/// **full announcement** via `GET /announcements/{id}` ([AnnouncementDetailCubit]),
/// rendering the body in the recipient's locale (Swahili first, English secondary —
/// ADR-0010).
///
/// Offline-first / data-frugal (PRD §15): the title + snippet from the feed item are
/// shown immediately (no blank screen) while the full body loads; on a network miss
/// the repository serves the last cached body if the announcement was opened before,
/// otherwise the snippet remains with a non-blocking retry. Nothing in flight is ever
/// shown — the backend returns only PUBLISHED, in-window announcements (else 404).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/theme/app_palette.dart';
import '../../../core/util/relative_time.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/announcement_detail_cubit.dart';
import '../data/feed_models.dart';

/// Shows one announcement's full content, seeded by the tapped [item].
class FeedDetailScreen extends StatelessWidget {
  /// Creates the screen for [item].
  const FeedDetailScreen({required this.item, super.key});

  /// The tapped feed item (provides the immediate title + snippet seed).
  final FeedItem item;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final localeCode = Localizations.localeOf(context).languageCode;
    return Scaffold(
      appBar: AppBar(title: Text(l10n.feedDetailTitle)),
      body: BlocBuilder<AnnouncementDetailCubit, AnnouncementDetailState>(
        builder: (context, state) {
          final announcement = state.announcement;
          // Prefer the full body once loaded; otherwise keep showing the snippet.
          final title = announcement?.title ?? item.title;
          final body = announcement?.bodyForLocale(localeCode) ?? item.snippet;
          final publishedAt = announcement?.publishAt ?? item.publishedAt;
          final loading =
              state.status == AnnouncementDetailStatus.loading &&
              announcement == null;
          final failed = state.status == AnnouncementDetailStatus.failure;
          return ListView(
            padding: const EdgeInsets.all(AppPalette.spaceLg),
            children: [
              // A small kind + relative-time header so the detail matches the
              // social feed card's voice.
              Row(
                children: [
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: AppPalette.spaceMd,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: Theme.of(
                        context,
                      ).colorScheme.primary.withValues(alpha: 0.12),
                      borderRadius: BorderRadius.circular(
                        AppPalette.radiusChip,
                      ),
                    ),
                    child: Text(
                      l10n.feedKindAnnouncement,
                      style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: Theme.of(context).colorScheme.primary,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  if (publishedAt != null) ...[
                    const SizedBox(width: AppPalette.spaceMd),
                    Text(
                      formatRelativeTime(l10n, publishedAt),
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
              ),
              const SizedBox(height: AppPalette.spaceLg),
              Text(title, style: Theme.of(context).textTheme.headlineSmall),
              const SizedBox(height: AppPalette.spaceLg),
              if (loading)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: AppPalette.spaceSm),
                  child: LinearProgressIndicator(),
                ),
              Text(body, style: Theme.of(context).textTheme.bodyLarge),
              // The full fetch failed but we still show the snippet — offer a
              // gentle retry rather than blocking the read (offline-first).
              if (failed) ...[
                const SizedBox(height: 16),
                Row(
                  children: [
                    Icon(
                      Icons.cloud_off,
                      size: 18,
                      color: Theme.of(context).colorScheme.outline,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        FailureMessages.of(l10n, state.error!),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ),
                    TextButton(
                      onPressed: () =>
                          context.read<AnnouncementDetailCubit>().load(),
                      child: Text(l10n.retryButton),
                    ),
                  ],
                ),
              ],
            ],
          );
        },
      ),
    );
  }
}
