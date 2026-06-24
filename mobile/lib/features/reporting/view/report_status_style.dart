/// Presentation helpers that turn a backend report status / event-type name into
/// localised, low-literacy-friendly UI (a colour, an icon, and Swahili-first
/// copy) — so the tracking timeline reads as a clear story, not raw enum codes.
///
/// WHY here and not in the model: status→colour/icon is a pure UI concern; the
/// [Report.status] string stays the backend's stable machine code (the contract),
/// and the citizen sees friendly copy + an icon (CLAUDE.md §8 — externalised
/// strings, icons + text for low literacy). Unknown codes degrade to a neutral
/// style rather than crashing, so a new backend status never breaks the screen.
library;

import 'package:flutter/material.dart';

import '../../../l10n/app_localizations.dart';

/// Maps a report lifecycle status to its UI presentation.
class ReportStatusStyle {
  const ReportStatusStyle._();

  /// A localised, human label for a backend [status] code.
  static String label(AppLocalizations l10n, String status) =>
      switch (status.toUpperCase()) {
        'NEW' || 'SUBMITTED' => l10n.statusNew,
        'ASSIGNED' || 'ACKNOWLEDGED' => l10n.statusAssigned,
        'IN_PROGRESS' => l10n.statusInProgress,
        'AWAITING_INFO' => l10n.statusAwaitingInfo,
        'RESOLVED' => l10n.statusResolved,
        'CLOSED' => l10n.statusClosed,
        'REOPENED' => l10n.statusReopened,
        'REJECTED' || 'DUPLICATE' => l10n.statusRejected,
        _ => status,
      };

  /// An icon evoking the [status] (icons + text for low-literacy clarity).
  static IconData icon(String status) => switch (status.toUpperCase()) {
    'NEW' || 'SUBMITTED' => Icons.fiber_new_outlined,
    'ASSIGNED' || 'ACKNOWLEDGED' => Icons.assignment_ind_outlined,
    'IN_PROGRESS' => Icons.build_circle_outlined,
    'AWAITING_INFO' => Icons.help_outline,
    'RESOLVED' => Icons.check_circle_outline,
    'CLOSED' => Icons.lock_outline,
    'REOPENED' => Icons.refresh,
    'REJECTED' || 'DUPLICATE' => Icons.block,
    _ => Icons.flag_outlined,
  };

  /// A theme-aware colour for the [status] chip/icon.
  static Color color(BuildContext context, String status) {
    final scheme = Theme.of(context).colorScheme;
    return switch (status.toUpperCase()) {
      'RESOLVED' || 'CLOSED' => Colors.green.shade700,
      'IN_PROGRESS' || 'ASSIGNED' || 'ACKNOWLEDGED' => scheme.primary,
      'AWAITING_INFO' || 'REOPENED' => Colors.orange.shade800,
      'REJECTED' || 'DUPLICATE' => scheme.error,
      _ => scheme.outline,
    };
  }

  /// A localised label for a case-event type on the timeline.
  static String eventLabel(AppLocalizations l10n, String eventType) =>
      switch (eventType.toUpperCase()) {
        'STATUS_CHANGE' => l10n.eventStatusChange,
        'COMMENT' => l10n.eventComment,
        'ASSIGNMENT' => l10n.eventAssignment,
        'RESOLUTION' => l10n.eventResolution,
        'REOPEN' || 'REOPENED' => l10n.eventReopened,
        'CONFIRMATION' => l10n.eventConfirmation,
        'CREATED' || 'SUBMITTED' => l10n.eventCreated,
        _ => eventType,
      };
}

/// Formats a UTC instant as a short, locale-light relative time the citizen can
/// read at a glance ("dakika 5 zilizopita"). Kept dependency-free (no intl
/// formatting needed) and Swahili-first via [AppLocalizations].
String formatRelativeTime(AppLocalizations l10n, DateTime? whenUtc) {
  if (whenUtc == null) return '';
  final now = DateTime.now().toUtc();
  final diff = now.difference(whenUtc);
  if (diff.inMinutes < 1) return l10n.timeJustNow;
  if (diff.inMinutes < 60) return l10n.timeMinutesAgo(diff.inMinutes);
  if (diff.inHours < 24) return l10n.timeHoursAgo(diff.inHours);
  return l10n.timeDaysAgo(diff.inDays);
}
