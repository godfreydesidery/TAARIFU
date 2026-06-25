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

// Re-exported so existing callers keep importing `formatRelativeTime` from here
// while the implementation now lives in the shared kernel (core/util) — see
// core/util/relative_time.dart (DRY, CLAUDE.md §3).
export '../../../core/util/relative_time.dart' show formatRelativeTime;

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

