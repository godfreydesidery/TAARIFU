/// Shared-kernel relative-time formatting.
///
/// WHY in core (not a feature): a friendly "X ago" label is needed by several
/// surfaces (report timeline, social feed, notifications), so it lives once in
/// the shared kernel rather than being copy-pasted per feature (DRY, CLAUDE.md
/// §3). Kept dependency-free (no `intl` formatting) and Swahili-first via
/// [AppLocalizations].
library;

import '../../l10n/app_localizations.dart';

/// Formats a UTC instant as a short relative time the citizen can read at a
/// glance ("dakika 5 zilizopita"). Returns an empty string for a `null` instant.
String formatRelativeTime(AppLocalizations l10n, DateTime? whenUtc) {
  if (whenUtc == null) return '';
  final now = DateTime.now().toUtc();
  final diff = now.difference(whenUtc.toUtc());
  if (diff.inMinutes < 1) return l10n.timeJustNow;
  if (diff.inMinutes < 60) return l10n.timeMinutesAgo(diff.inMinutes);
  if (diff.inHours < 24) return l10n.timeHoursAgo(diff.inHours);
  return l10n.timeDaysAgo(diff.inDays);
}
