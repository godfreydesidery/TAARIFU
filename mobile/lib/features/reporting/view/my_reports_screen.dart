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
      body: BlocBuilder<MyReportsCubit, MyReportsState>(
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
                  for (final d in state.drafts) _DraftTile(entry: d),
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

/// A queued offline draft, with its pending/failed status and a discard action.
class _DraftTile extends StatelessWidget {
  const _DraftTile({required this.entry});

  final OutboxEntry entry;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final failed = entry.status == OutboxStatus.failed;
    final title = entry.payload['title'] as String? ?? '';
    return Card(
      child: ListTile(
        leading: Icon(
          failed ? Icons.error_outline : Icons.cloud_upload_outlined,
          color: failed ? Theme.of(context).colorScheme.error : null,
        ),
        title: Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
        subtitle: Text(failed ? l10n.draftFailed : l10n.draftPending),
        trailing: IconButton(
          tooltip: l10n.draftDiscard,
          icon: const Icon(Icons.delete_outline),
          onPressed: () =>
              context.read<MyReportsCubit>().discardDraft(entry.localId),
        ),
      ),
    );
  }
}

/// A filed report row showing its ticket code + status.
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
                label: Text(l10n.reportConfirmPrompt),
                visualDensity: VisualDensity.compact,
              )
            : Text(report.status),
        isThreeLine: false,
        onTap: onTap,
      ),
    );
  }
}
