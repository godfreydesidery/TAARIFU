/// The social feed card: an elegant, rich presentation of one [FeedItem].
///
/// WHY a dedicated, const-friendly widget: the feed is the app's home and the
/// most-scrolled surface, so the card must be beautiful AND cheap to build on a
/// 1GB Android — small const subtrees, a single image decode at a capped width,
/// and no heavy shadows (a hairline-outlined rounded card from the theme). The
/// card carries the social affordances citizens expect (author + area chips, a
/// kind badge, a relative timestamp, and a support/share row) while staying
/// strictly civic and non-partisan: the "support" reaction is a neutral
/// acknowledgement, never a vote, and tokens/identity tiers never gate browsing
/// (PRD §19 — tokens never buy democratic weight).
library;

import 'package:flutter/material.dart';

import '../../../core/theme/app_palette.dart';
import '../../../core/util/relative_time.dart';
import '../../../l10n/app_localizations.dart';
import '../data/feed_models.dart';

/// A single, tappable social-feed card for [item].
class FeedCard extends StatelessWidget {
  /// Creates the card for [item]; [onTap] opens the full detail and [dataSaver]
  /// suppresses the cover image so a low-bundle citizen is never charged for it.
  /// [onAct], when supplied, drives the inline civic action affordance shown for
  /// actionable kinds (petition → Sign, poll → Respond); the shell routes it to
  /// the engagement hub where the real, tier-gated action lives.
  const FeedCard({
    required this.item,
    required this.onTap,
    this.onAct,
    this.dataSaver = false,
    super.key,
  });

  /// The feed item to render.
  final FeedItem item;

  /// Opens the full item (the detail screen).
  final VoidCallback onTap;

  /// Opens the inline civic action for an actionable kind, or `null`.
  final VoidCallback? onAct;

  /// When true, the cover image is not loaded (data-frugal, PRD §15).
  final bool dataSaver;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final style = _FeedKindStyle.of(item.kind);
    final accent = style.color(scheme);
    final hasImage = item.imageUrl != null && !dataSaver;

    return Card(
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (hasImage) _CoverImage(url: item.imageUrl!, accent: accent),
            Padding(
              padding: const EdgeInsets.all(AppPalette.spaceLg),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Header(item: item, accent: accent, style: style, l10n: l10n),
                  const SizedBox(height: AppPalette.spaceMd),
                  Text(
                    item.title,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (item.snippet.isNotEmpty) ...[
                    const SizedBox(height: AppPalette.spaceXs),
                    Text(
                      item.snippet,
                      maxLines: 3,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                  if (item.areaName != null) ...[
                    const SizedBox(height: AppPalette.spaceMd),
                    _AreaChip(area: item.areaName!),
                  ],
                  const SizedBox(height: AppPalette.spaceSm),
                  const Divider(height: AppPalette.spaceXl),
                  _ActionRow(
                    item: item,
                    accent: accent,
                    onOpen: onTap,
                    onAct: onAct,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// The card header: an author avatar, author/issuer name, and a kind badge with
/// a relative timestamp.
class _Header extends StatelessWidget {
  const _Header({
    required this.item,
    required this.accent,
    required this.style,
    required this.l10n,
  });

  final FeedItem item;
  final Color accent;
  final _FeedKindStyle style;
  final AppLocalizations l10n;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final author = item.authorName;
    final time = formatRelativeTime(l10n, item.publishedAt);
    return Row(
      children: [
        CircleAvatar(
          radius: 20,
          backgroundColor: accent.withValues(alpha: 0.15),
          foregroundColor: accent,
          child: Icon(style.icon, size: 20),
        ),
        const SizedBox(width: AppPalette.spaceMd),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                author ?? l10n.appTitle,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.titleSmall,
              ),
              const SizedBox(height: 2),
              Row(
                children: [
                  _KindBadge(label: style.label(l10n), color: accent),
                  if (time.isNotEmpty) ...[
                    const SizedBox(width: AppPalette.spaceSm),
                    Text(
                      '· $time',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

/// A small, coloured pill marking the item's kind (Announcement/Report/…).
class _KindBadge extends StatelessWidget {
  const _KindBadge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.14),
      borderRadius: BorderRadius.circular(AppPalette.radiusChip),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall?.copyWith(
        color: color,
        fontWeight: FontWeight.w700,
      ),
    ),
  );
}

/// A teal location chip for the item's area (Kata/Wilaya).
class _AreaChip extends StatelessWidget {
  const _AreaChip({required this.area});

  final String area;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      const Icon(
        Icons.place_outlined,
        size: 16,
        color: AppPalette.teal,
      ),
      const SizedBox(width: AppPalette.spaceXs),
      Flexible(
        child: Text(
          area,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(
            color: AppPalette.teal,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    ],
  );
}

/// The engagement row: a neutral "support" acknowledgement (with count), a
/// share affordance, and either a kind-aware civic CTA (Sign/Respond, for an
/// actionable petition/poll when [onAct] is wired) or a read-more nudge. The
/// reaction/share glyphs are presentation-only — nothing here fabricates a count
/// the server did not send; the inline CTA routes to the engagement hub where the
/// real, one-person-one-vote, tier-gated action is performed (PRD §19 — tokens
/// never buy democratic weight; the feed never votes inline behind the contract).
class _ActionRow extends StatelessWidget {
  const _ActionRow({
    required this.item,
    required this.accent,
    required this.onOpen,
    this.onAct,
  });

  final FeedItem item;
  final Color accent;
  final VoidCallback onOpen;
  final VoidCallback? onAct;

  /// The localised inline-CTA label for an actionable kind, or `null` when the
  /// kind has no civic action (announcement/report → just "read more").
  String? _ctaLabel(AppLocalizations l10n) => switch (item.kind) {
    FeedItemKind.petition => l10n.feedActionSign,
    FeedItemKind.poll => l10n.feedActionRespond,
    _ => null,
  };

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final cta = _ctaLabel(l10n);
    final showCta = cta != null && onAct != null;
    return Row(
      children: [
        Icon(Icons.favorite_outline, size: 20, color: scheme.onSurfaceVariant),
        if (item.reactionCount > 0) ...[
          const SizedBox(width: AppPalette.spaceXs),
          Text(
            l10n.feedReactCount(item.reactionCount),
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
        const SizedBox(width: AppPalette.spaceLg),
        Icon(Icons.ios_share_outlined, size: 20, color: scheme.onSurfaceVariant),
        const Spacer(),
        if (showCta)
          FilledButton.tonal(
            onPressed: onAct,
            style: FilledButton.styleFrom(
              visualDensity: VisualDensity.compact,
              foregroundColor: accent,
            ),
            child: Text(cta),
          )
        else
          TextButton(
            onPressed: onOpen,
            style: TextButton.styleFrom(foregroundColor: accent),
            child: Text(l10n.feedReadMore),
          ),
      ],
    );
  }
}

/// The card's cover image, decoded at a capped width for the data budget and
/// degrading to a tinted placeholder on a load failure (never a broken icon).
class _CoverImage extends StatelessWidget {
  const _CoverImage({required this.url, required this.accent});

  final String url;
  final Color accent;

  @override
  Widget build(BuildContext context) => AspectRatio(
    aspectRatio: 16 / 9,
    child: Image.network(
      url,
      fit: BoxFit.cover,
      // Decode at a modest width — full-resolution decodes are wasteful on a
      // small, low-memory screen (PRD §15 performance + data budget).
      cacheWidth: 720,
      loadingBuilder: (context, child, progress) =>
          progress == null ? child : _ImagePlaceholder(accent: accent),
      errorBuilder: (context, _, _) => _ImagePlaceholder(accent: accent),
    ),
  );
}

/// A soft tinted block standing in for a loading/failed cover image.
class _ImagePlaceholder extends StatelessWidget {
  const _ImagePlaceholder({required this.accent});

  final Color accent;

  @override
  Widget build(BuildContext context) => ColoredBox(
    color: accent.withValues(alpha: 0.10),
    child: Center(
      child: Icon(
        Icons.image_outlined,
        color: accent.withValues(alpha: 0.5),
        size: 32,
      ),
    ),
  );
}

/// Maps a [FeedItemKind] to its badge label, icon, and accent colour.
class _FeedKindStyle {
  const _FeedKindStyle(this.icon, this._color, this._label);

  final IconData icon;
  final Color Function(ColorScheme) _color;
  final String Function(AppLocalizations) _label;

  /// The accent colour for this kind, resolved against the active [scheme].
  Color color(ColorScheme scheme) => _color(scheme);

  /// The localised badge label for this kind.
  String label(AppLocalizations l10n) => _label(l10n);

  /// Resolves the style for a [kind].
  static _FeedKindStyle of(FeedItemKind kind) => switch (kind) {
    FeedItemKind.announcement => _FeedKindStyle(
      Icons.campaign_outlined,
      (s) => s.primary,
      (l) => l.feedKindAnnouncement,
    ),
    FeedItemKind.report => _FeedKindStyle(
      Icons.report_gmailerrorred_outlined,
      (_) => AppPalette.teal,
      (l) => l.feedKindReport,
    ),
    FeedItemKind.petition => _FeedKindStyle(
      Icons.draw_outlined,
      (_) => AppPalette.amber,
      (l) => l.feedKindPetition,
    ),
    FeedItemKind.poll => _FeedKindStyle(
      Icons.how_to_vote_outlined,
      (s) => s.tertiary,
      (l) => l.feedKindPoll,
    ),
  };
}
