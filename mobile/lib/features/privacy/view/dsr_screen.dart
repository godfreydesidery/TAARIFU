/// The PDPA data-request (DSR) self-service screen (Haki za data) — PRD §18,
/// §25.1; UC-A16/UC-A17.
///
/// Lets a citizen exercise their two PDPA rights on their own data:
///   * ACCESS — request an export of the personal data held about them;
///   * ERASURE — request deletion (de-identification + tombstoning, not raw
///     deletion — the civic record is kept de-identified).
/// Each is opened with one tap; erasure is behind a confirm dialog (it is
/// consequential and largely irreversible). The screen lists the citizen's open
/// requests with their status + SLA so they can see the obligation being met
/// (acknowledge ≤72h, complete ≤30 days). Degrades gracefully: a backend block
/// on erasure (e.g. an active staff role) surfaces as a clear localised message,
/// not a silent failure.
library;

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../core/network/failure_messages.dart';
import '../../../core/util/relative_time.dart';
import '../../../core/widgets/status_views.dart';
import '../../../l10n/app_localizations.dart';
import '../bloc/dsr_cubit.dart';
import '../data/dsr_models.dart';

/// The data-rights self-service view.
class DsrScreen extends StatelessWidget {
  /// Creates the screen.
  const DsrScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.dsrTitle)),
      body: BlocConsumer<DsrCubit, DsrState>(
        listenWhen: (p, c) =>
            (c.error != null && p.error != c.error) ||
            (c.action != DsrAction.none && p.action != c.action),
        listener: (context, state) {
          final messenger = ScaffoldMessenger.of(context);
          if (state.error != null) {
            messenger
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(content: Text(FailureMessages.of(l10n, state.error!))),
              );
          } else if (state.action != DsrAction.none) {
            messenger
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(content: Text(_actionMessage(l10n, state.action))),
              );
            context.read<DsrCubit>().clearAction();
          }
        },
        builder: (context, state) {
          switch (state.status) {
            case DsrStatus.initial:
            case DsrStatus.loading:
              return LoadingView(label: l10n.loadingLabel);
            case DsrStatus.failure:
              return ErrorRetryView(
                message: FailureMessages.of(l10n, state.error!),
                retryLabel: l10n.retryButton,
                onRetry: () => context.read<DsrCubit>().load(),
              );
            case DsrStatus.loaded:
              return _content(context, l10n, state);
          }
        },
      ),
    );
  }

  String _actionMessage(AppLocalizations l10n, DsrAction a) => switch (a) {
    DsrAction.accessOpened => l10n.dsrAccessOpenedNote,
    DsrAction.erasureOpened => l10n.dsrErasureOpenedNote,
    DsrAction.none => '',
  };

  Widget _content(BuildContext context, AppLocalizations l10n, DsrState state) {
    final busy = state.actionInFlight;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Text(l10n.dsrIntro, style: Theme.of(context).textTheme.bodyMedium),
        const SizedBox(height: 16),
        _RightCard(
          icon: Icons.download_rounded,
          title: l10n.dsrAccessTitle,
          body: l10n.dsrAccessBody,
          buttonLabel: l10n.dsrAccessButton,
          onPressed: busy ? null : () => context.read<DsrCubit>().requestAccess(),
        ),
        const SizedBox(height: 12),
        _RightCard(
          icon: Icons.delete_forever_rounded,
          title: l10n.dsrErasureTitle,
          body: l10n.dsrErasureBody,
          buttonLabel: l10n.dsrErasureButton,
          destructive: true,
          onPressed: busy ? null : () => _confirmErasure(context, l10n),
        ),
        const Divider(height: 32),
        Text(
          l10n.dsrMyRequestsHeader,
          style: Theme.of(context).textTheme.titleMedium,
        ),
        const SizedBox(height: 8),
        if (state.requests.isEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              l10n.dsrNoRequests,
              style: Theme.of(context).textTheme.bodySmall,
            ),
          )
        else
          for (final request in state.requests)
            _RequestTile(request: request),
      ],
    );
  }

  /// Erasure is consequential — gate it behind an explicit confirmation.
  Future<void> _confirmErasure(
    BuildContext context,
    AppLocalizations l10n,
  ) async {
    final cubit = context.read<DsrCubit>();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: Text(l10n.dsrErasureConfirmTitle),
        content: Text(l10n.dsrErasureConfirmBody),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.cancelButton),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.dsrErasureConfirmButton),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await cubit.requestErasure();
    }
  }
}

/// A card presenting one PDPA right with an explainer and a call-to-action.
class _RightCard extends StatelessWidget {
  const _RightCard({
    required this.icon,
    required this.title,
    required this.body,
    required this.buttonLabel,
    required this.onPressed,
    this.destructive = false,
  });

  final IconData icon;
  final String title;
  final String body;
  final String buttonLabel;
  final VoidCallback? onPressed;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final accent = destructive ? scheme.error : scheme.primary;
    return Card(
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Icon(icon, color: accent),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(body, style: Theme.of(context).textTheme.bodyMedium),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerRight,
              child: destructive
                  ? OutlinedButton(
                      onPressed: onPressed,
                      style: OutlinedButton.styleFrom(foregroundColor: accent),
                      child: Text(buttonLabel),
                    )
                  : FilledButton(
                      onPressed: onPressed,
                      child: Text(buttonLabel),
                    ),
            ),
          ],
        ),
      ),
    );
  }
}

/// One tracked request row: its type, a status chip, and the relative due/ack time.
class _RequestTile extends StatelessWidget {
  const _RequestTile({required this.request});

  final DataSubjectRequest request;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final typeLabel = request.type == DsrType.access
        ? l10n.dsrAccessTitle
        : l10n.dsrErasureTitle;
    final subtitle = request.dueAt != null && !request.isTerminal
        ? l10n.dsrDueLabel(formatRelativeTime(l10n, request.dueAt))
        : (request.legalHold ? l10n.dsrLegalHoldNote : null);
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: Icon(
          request.type == DsrType.access
              ? Icons.download_rounded
              : Icons.delete_forever_rounded,
        ),
        title: Text(typeLabel),
        subtitle: subtitle == null ? null : Text(subtitle),
        trailing: Chip(
          label: Text(_statusLabel(l10n, request.status)),
          visualDensity: VisualDensity.compact,
        ),
      ),
    );
  }

  String _statusLabel(AppLocalizations l10n, String status) => switch (status) {
    'RECEIVED' => l10n.dsrStatusReceived,
    'ACKNOWLEDGED' => l10n.dsrStatusAcknowledged,
    'IN_PROGRESS' => l10n.dsrStatusInProgress,
    'COMPLETED' => l10n.dsrStatusCompleted,
    'REJECTED' => l10n.dsrStatusRejected,
    'ON_HOLD' => l10n.dsrStatusOnHold,
    _ => status,
  };
}
