/// The "My reports" screen (Ripoti Zangu) — US-3.2.
///
/// Shows two sections: queued **offline drafts** (waiting to sync, always shown
/// even with no network) and the citizen's **filed reports** from the server.
/// A "Send now" action flushes the outbox; tapping a filed report opens its
/// tracking/timeline detail.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/storage/outbox_entry.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/my_reports_cubit.dart';
import '../data/reporting_models.dart';
import 'report_status_style.dart';

/// The my-reports list.
class MyReportsScreen extends StatelessWidget {
  /// Creates the screen. [onOpenReport] navigates to a report's detail.
  const MyReportsScreen({required this.onOpenReport, super.key});

  /// Opens the tracking/detail screen for a filed report id.
  final void Function(String reportId) onOpenReport;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(
        title: Text(l10n.myReportsTitle),
        actions: [
          BlocBuilder<MyReportsCubit, MyReportsState>(
            buildWhen: (p, c) =>
                p.syncing != c.syncing || p.drafts.length != c.drafts.length,
            builder: (context, state) {
              if (state.drafts.isEmpty) return const SizedBox.shrink();
              return TextButton(
                onPressed: state.syncing
                    ? null
                    : () => context.read<MyReportsCubit>().syncNow(),
                child: Text(
                  state.syncing
                      ? l10n.myReportsSyncing
                      : l10n.myReportsSyncButton,
                ),
              );
            },
          ),
        ],
      ),
      body: BlocConsumer<MyReportsCubit, MyReportsState>(
        // Reconnect/sync feedback: confirm how many drafts were just sent.
        listenWhen: (p, c) =>
            c.justSyncedCount > 0 && p.justSyncedCount != c.justSyncedCount,
        listener: (context, state) {
          ScaffoldMessenger.of(context)
            ..hideCurrentSnackBar()
            ..showSnackBar(
              SnackBar(content: Text(l10n.draftSyncedFeedback(state.justSyncedCount))),
            );
        },
        builder: (context, state) {
          if (state.status == MyReportsStatus.loading &&
              state.reports.isEmpty &&
              state.drafts.isEmpty) {
            return LoadingView(label: l10n.loadingLabel);
          }
          if (state.status == MyReportsStatus.failure &&
              state.reports.isEmpty &&
              state.drafts.isEmpty) {
            return ErrorRetryView(
              message: FailureMessages.of(l10n, state.error!),
              retryLabel: l10n.retryButton,
              onRetry: () => context.read<MyReportsCubit>().load(),
            );
          }
          if (state.reports.isEmpty && state.drafts.isEmpty) {
            return EmptyView(message: l10n.myReportsEmpty);
          }
          return RefreshIndicator(
            onRefresh: () => context.read<MyReportsCubit>().load(),
            child: ListView(
              padding: const EdgeInsets.all(12),
              children: [
                if (state.drafts.isNotEmpty) ...[
                  _SectionHeader(title: l10n.myReportsDraftsHeader),
                  // Reassures the citizen the queue auto-flushes on reconnect —
                  // no duplicates (the idempotency key guards every replay).
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 4,
                      vertical: 4,
                    ),
                    child: Row(
                      children: [
                        Icon(
                          Icons.info_outline,
                          size: 16,
                          color: Theme.of(context).colorScheme.outline,
                        ),
                        const SizedBox(width: 6),
                        Expanded(
                          child: Text(
                            l10n.draftAutoSyncNote,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ),
                      ],
                    ),
                  ),
                  for (final d in state.drafts)
                    _DraftTile(entry: d, syncing: state.syncing),
                  const SizedBox(height: 8),
                ],
                if (state.reports.isNotEmpty) ...[
                  _SectionHeader(title: l10n.myReportsServerHeader),
                  for (final r in state.reports)
                    _ReportTile(
                      report: r,
                      onTap: () => onOpenReport(r.id),
                    ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.title});

  final String title;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 4),
    child: Text(title, style: Theme.of(context).textTheme.titleSmall),
  );
}

/// A queued offline draft: a coloured pending/failed badge, an attachment count,
/// a retry note, and discard + per-draft "send now" actions.
class _DraftTile extends StatelessWidget {
  const _DraftTile({required this.entry, required this.syncing});

  final OutboxEntry entry;

  /// Whether a sync flush is currently running (disables per-draft actions).
  final bool syncing;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final failed = entry.status == OutboxStatus.failed;
    final sending = entry.status == OutboxStatus.sending;
    final title = entry.payload['title'] as String? ?? '';
    final attachmentCount =
        (entry.payload['attachmentRefs'] as List?)?.length ?? 0;
    final badgeColor = failed ? scheme.error : scheme.tertiary;
    return Card(
      child: ListTile(
        leading: Icon(
          failed
              ? Icons.error_outline
              : (sending ? Icons.sync : Icons.cloud_upload_outlined),
          color: badgeColor,
        ),
        title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // The pending badge: a clear, coloured status pill (low-literacy).
            Wrap(
              spacing: 6,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                _Badge(
                  label: sending
                      ? l10n.draftSending
                      : (failed ? l10n.draftFailed : l10n.draftPending),
                  color: badgeColor,
                ),
                if (attachmentCount > 0)
                  _Badge(
                    label: l10n.draftAttachmentCount(attachmentCount),
                    color: scheme.outline,
                  ),
              ],
            ),
            if (failed && entry.attempts > 0)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(
                  l10n.draftRetryNote(entry.attempts),
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
          ],
        ),
        isThreeLine: failed && entry.attempts > 0,
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              tooltip: l10n.myReportsSyncButton,
              icon: const Icon(Icons.send_outlined),
              onPressed: syncing
                  ? null
                  : () => context.read<MyReportsCubit>().syncNow(),
            ),
            IconButton(
              tooltip: l10n.draftDiscard,
              icon: const Icon(Icons.delete_outline),
              onPressed: syncing
                  ? null
                  : () =>
                        context.read<MyReportsCubit>().discardDraft(entry.localId),
            ),
          ],
        ),
      ),
    );
  }
}

/// A small coloured status pill.
class _Badge extends StatelessWidget {
  const _Badge({required this.label, required this.color});

  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
    decoration: BoxDecoration(
      color: color.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(12),
    ),
    child: Text(
      label,
      style: Theme.of(context).textTheme.labelSmall?.copyWith(color: color),
    ),
  );
}

/// A filed report row showing its ticket code + a localised status chip.
class _ReportTile extends StatelessWidget {
  const _ReportTile({required this.report, required this.onTap});

  final Report report;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Card(
      child: ListTile(
        title: Text(report.title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(report.code),
        trailing: report.awaitingConfirmation
            ? Chip(
                avatar: const Icon(Icons.help_outline, size: 18),
                label: Text(l10n.reportConfirmPrompt),
                visualDensity: VisualDensity.compact,
              )
            : Chip(
                avatar: Icon(
                  ReportStatusStyle.icon(report.status),
                  size: 16,
                  color: ReportStatusStyle.color(context, report.status),
                ),
                label: Text(ReportStatusStyle.label(l10n, report.status)),
                visualDensity: VisualDensity.compact,
              ),
        isThreeLine: false,
        onTap: onTap,
      ),
    );
  }
}
