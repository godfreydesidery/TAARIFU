/// The report tracking/detail screen (Ufuatiliaji wa ripoti) — US-3.2, US-3.5,
/// UC-D05/D11/D12.
///
/// Shows the report header (code + status), the case **timeline**, an add-info
/// comment box, and — when the case is RESOLVED and still awaiting the citizen's
/// decision — the **confirm/dispute** controls (Confirm → CLOSED, Dispute →
/// REOPENED with an optional reason).
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/report_detail_cubit.dart';
import '../data/reporting_models.dart';
import 'report_status_style.dart';

/// The tracking/detail view for one of the caller's own reports.
class ReportDetailScreen extends StatefulWidget {
  /// Creates the screen.
  const ReportDetailScreen({super.key});

  @override
  State<ReportDetailScreen> createState() => _ReportDetailScreenState();
}

class _ReportDetailScreenState extends State<ReportDetailScreen> {
  final _commentController = TextEditingController();
  final _reasonController = TextEditingController();

  @override
  void dispose() {
    _commentController.dispose();
    _reasonController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.reportDetailTitle)),
      body: BlocConsumer<ReportDetailCubit, ReportDetailState>(
        listenWhen: (p, c) => p.error != c.error && c.error != null,
        listener: (context, state) {
          ScaffoldMessenger.of(context)
            ..hideCurrentSnackBar()
            ..showSnackBar(
              SnackBar(content: Text(FailureMessages.of(l10n, state.error!))),
            );
        },
        builder: (context, state) {
          switch (state.status) {
            case ReportDetailStatus.initial:
            case ReportDetailStatus.loading:
              return LoadingView(label: l10n.loadingLabel);
            case ReportDetailStatus.failure:
              return ErrorRetryView(
                message: FailureMessages.of(l10n, state.error!),
                retryLabel: l10n.retryButton,
                onRetry: () => context.read<ReportDetailCubit>().load(),
              );
            case ReportDetailStatus.loaded:
              return _content(context, l10n, state);
          }
        },
      ),
    );
  }

  Widget _content(
    BuildContext context,
    AppLocalizations l10n,
    ReportDetailState state,
  ) {
    final report = state.report!;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(report.title, style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 4),
        Text(report.code),
        const SizedBox(height: 8),
        Row(
          children: [
            Text('${l10n.reportStatusLabel}: '),
            Chip(
              avatar: Icon(
                ReportStatusStyle.icon(report.status),
                size: 18,
                color: ReportStatusStyle.color(context, report.status),
              ),
              label: Text(ReportStatusStyle.label(l10n, report.status)),
              visualDensity: VisualDensity.compact,
            ),
          ],
        ),
        if (report.dueAt != null) ...[
          const SizedBox(height: 4),
          Text(
            l10n.reportDueLabel(formatRelativeTime(l10n, report.dueAt)),
            style: Theme.of(context).textTheme.bodySmall,
          ),
        ],
        if (report.description != null && report.description!.isNotEmpty) ...[
          const SizedBox(height: 12),
          Text(report.description!),
        ],
        if (report.resolution != null && report.resolution!.isNotEmpty) ...[
          const SizedBox(height: 16),
          Text(
            l10n.reportResolutionHeader,
            style: Theme.of(context).textTheme.titleSmall,
          ),
          Text(report.resolution!),
        ],
        if (report.awaitingConfirmation)
          _confirmBlock(context, l10n, state),
        const SizedBox(height: 24),
        Text(
          l10n.reportTimelineHeader,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        if (state.timeline.isEmpty)
          Text(l10n.reportTimelineEmpty)
        else
          for (var i = 0; i < state.timeline.length; i++)
            _TimelineTile(
              event: state.timeline[i],
              isLast: i == state.timeline.length - 1,
            ),
        const SizedBox(height: 16),
        _commentBlock(context, l10n, state),
      ],
    );
  }

  /// The confirm/dispute controls, shown only while RESOLVED + unconfirmed.
  Widget _confirmBlock(
    BuildContext context,
    AppLocalizations l10n,
    ReportDetailState state,
  ) {
    final busy = state.actionInFlight;
    return Card(
      color: Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              l10n.reportConfirmPrompt,
              style: Theme.of(context).textTheme.titleSmall,
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _reasonController,
              decoration: InputDecoration(
                labelText: l10n.reportDisputeReasonLabel,
              ),
              maxLines: 2,
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: busy
                        ? null
                        : () => context
                              .read<ReportDetailCubit>()
                              .confirmResolution(
                                confirmed: false,
                                reason: _reasonText(),
                              ),
                    child: Text(l10n.reportDisputeButton),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton(
                    onPressed: busy
                        ? null
                        : () => context
                              .read<ReportDetailCubit>()
                              .confirmResolution(confirmed: true),
                    child: Text(l10n.reportConfirmButton),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _commentBlock(
    BuildContext context,
    AppLocalizations l10n,
    ReportDetailState state,
  ) {
    return Row(
      children: [
        Expanded(
          child: TextField(
            controller: _commentController,
            decoration: InputDecoration(labelText: l10n.reportAddCommentLabel),
            minLines: 1,
            maxLines: 3,
          ),
        ),
        IconButton(
          tooltip: l10n.sendButton,
          icon: const Icon(Icons.send),
          onPressed: state.actionInFlight
              ? null
              : () {
                  final text = _commentController.text.trim();
                  if (text.isEmpty) return;
                  context.read<ReportDetailCubit>().addComment(text);
                  _commentController.clear();
                },
        ),
      ],
    );
  }

  String? _reasonText() {
    final t = _reasonController.text.trim();
    return t.isEmpty ? null : t;
  }
}

/// One timeline entry: a left rail connector + a localised event label, the
/// citizen-readable message, and a relative timestamp ("dakika 5 zilizopita").
class _TimelineTile extends StatelessWidget {
  const _TimelineTile({required this.event, required this.isLast});

  final CaseEvent event;

  /// Whether this is the final entry (no trailing connector line).
  final bool isLast;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final scheme = Theme.of(context).colorScheme;
    final eventLabel = ReportStatusStyle.eventLabel(l10n, event.eventType);
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // The vertical timeline rail: a dot with a connecting line below it.
          Column(
            children: [
              Icon(
                event.publicEvent ? Icons.circle : Icons.lock_outline,
                size: 12,
                color: scheme.primary,
              ),
              if (!isLast)
                Expanded(
                  child: Container(width: 2, color: scheme.outlineVariant),
                ),
            ],
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.only(bottom: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          eventLabel,
                          style: Theme.of(context).textTheme.labelLarge,
                        ),
                      ),
                      Text(
                        formatRelativeTime(l10n, event.createdAt),
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                  if (event.message != null && event.message!.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(event.message!),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
